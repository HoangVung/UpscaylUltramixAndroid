#include "realesrgan.h"
#include <android/log.h>
#include <cstdio>
#include <algorithm>
#include <cstring>
#include <chrono>
#include <mutex>
#include "ncnn/gpu.h"

#define STB_IMAGE_IMPLEMENTATION
#define STBI_NO_THREAD_LOCALS
#include "stb_image.h"

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

#define LOG_TAG "RealESRGAN"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const int TILE_SIZE = 128;
static const int TILE_PAD = 10;
static const int MAX_INPUT_PIXELS = 6000000;
static const int MAX_INPUT_LONG_EDGE = 3000;
static std::mutex g_gpu_mutex;
static bool g_gpu_instance_ready = false;

static bool init_vulkan_once()
{
#if NCNN_VULKAN
    std::lock_guard<std::mutex> lock(g_gpu_mutex);
    if (g_gpu_instance_ready) {
        return ncnn::get_gpu_count() > 0;
    }

    int gpu_ret = ncnn::create_gpu_instance();
    int gpu_count = gpu_ret == 0 ? ncnn::get_gpu_count() : 0;
    g_gpu_instance_ready = gpu_count > 0;
    LOGI("Vulkan init result: create_gpu_instance=%d gpu_count=%d", gpu_ret, gpu_count);
    return g_gpu_instance_ready;
#else
    return false;
#endif
}

static long long elapsed_ms(std::chrono::steady_clock::time_point start)
{
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - start
    ).count();
}

RealESRGAN::RealESRGAN()
{
    scale = 4;
    cpu_fallback_active = false;
    cpu_net_loaded = false;

    vulkan_enabled = init_vulkan_once();
    net.opt.use_vulkan_compute = vulkan_enabled;

#if NCNN_VULKAN
    if (vulkan_enabled) {
        net.set_vulkan_device(0);
        net.opt.use_fp16_packed = true;
        net.opt.use_fp16_storage = true;
        net.opt.use_fp16_arithmetic = false;  // disabled to reduce Vulkan errors
        LOGI("Vulkan enabled, using GPU mode (fp16_arithmetic=off)");
        return;
    }
#endif

    net.opt.use_fp16_packed = false;
    net.opt.use_fp16_storage = false;
    net.opt.use_fp16_arithmetic = false;
    LOGI("Vulkan unavailable, using CPU mode");
}

RealESRGAN::~RealESRGAN()
{
    net.clear();
    cpu_net.clear();
}

int RealESRGAN::load(const std::string& param_path, const std::string& bin_path)
{
    cached_param_path = param_path;
    cached_bin_path = bin_path;

    auto start = std::chrono::steady_clock::now();
    int r1 = net.load_param(param_path.c_str());
    int r2 = net.load_model(bin_path.c_str());
    LOGI("Model load finished in %lld ms (param=%d model=%d mode=%s)",
         elapsed_ms(start), r1, r2, vulkan_enabled ? "Vulkan" : "CPU");
    if (r1 != 0 || r2 != 0) return -1;
    return 0;
}

int RealESRGAN::load_cpu_net()
{
    if (cpu_net_loaded) return 0;

    cpu_net.opt.use_vulkan_compute = false;
    cpu_net.opt.use_fp16_packed = false;
    cpu_net.opt.use_fp16_storage = false;
    cpu_net.opt.use_fp16_arithmetic = false;

    auto start = std::chrono::steady_clock::now();
    int r1 = cpu_net.load_param(cached_param_path.c_str());
    int r2 = cpu_net.load_model(cached_bin_path.c_str());
    LOGI("CPU fallback model load finished in %lld ms (param=%d model=%d)",
         elapsed_ms(start), r1, r2);
    if (r1 != 0 || r2 != 0) return -1;
    cpu_net_loaded = true;
    return 0;
}

int RealESRGAN::run_tile_cpu(const ncnn::Mat& in, ncnn::Mat& out)
{
    if (load_cpu_net() != 0) return -101;

    ncnn::Extractor ex = cpu_net.create_extractor();
    int ret_input = ex.input("data", in);
    if (ret_input != 0) return -4;

    int ret_extract = ex.extract("output", out);
    if (ret_extract != 0) return -5;

    return 0;
}

bool RealESRGAN::using_vulkan() const
{
    return vulkan_enabled && !cpu_fallback_active;
}

const char* RealESRGAN::mode_label() const
{
    if (cpu_fallback_active) return "CPU fallback";
    if (vulkan_enabled) return "Vulkan";
    return "CPU";
}

static void extract_tile(const unsigned char* src, int src_w, int src_h,
                         int tile_x, int tile_y,
                         unsigned char* tile, int tile_w, int tile_h, int pad)
{
    int src_x0 = tile_x - pad;
    int src_y0 = tile_y - pad;
    int src_x1 = tile_x + TILE_SIZE + pad;
    int src_y1 = tile_y + TILE_SIZE + pad;

    int dst_offset_x = 0;
    int dst_offset_y = 0;
    if (src_x0 < 0) { dst_offset_x = -src_x0; src_x0 = 0; }
    if (src_y0 < 0) { dst_offset_y = -src_y0; src_y0 = 0; }
    if (src_x1 > src_w) src_x1 = src_w;
    if (src_y1 > src_h) src_y1 = src_h;

    int copy_w = src_x1 - src_x0;
    int copy_h = src_y1 - src_y0;

    memset(tile, 0, tile_w * tile_h * 3);

    for (int y = 0; y < copy_h; y++) {
        int src_y = src_y0 + y;
        int dst_y = dst_offset_y + y;
        const unsigned char* src_row = src + (src_y * src_w + src_x0) * 3;
        unsigned char* dst_row = tile + (dst_y * tile_w + dst_offset_x) * 3;
        memcpy(dst_row, src_row, copy_w * 3);
    }
}

int RealESRGAN::process(
    const std::string& input_path,
    const std::string& output_path,
    const std::function<bool(int, int, const char*, long long, bool)>& progress,
    const std::atomic_bool& cancel_requested
)
{
    auto start = std::chrono::steady_clock::now();
    progress(0, 0, "Đang đọc ảnh đầu vào", elapsed_ms(start), using_vulkan());

    int w, h, c;
    unsigned char* pixeldata = stbi_load(input_path.c_str(), &w, &h, &c, 3);
    if (!pixeldata) {
        LOGE("stbi_load failed for: %s", input_path.c_str());
        return -2;
    }
    if (w <= 0 || h <= 0) {
        LOGE("Invalid input dims: w=%d h=%d", w, h);
        stbi_image_free(pixeldata);
        return -2;
    }

    // Check size limit
    long long pixels = (long long)w * h;
    int long_edge = (w > h) ? w : h;
    if (pixels > MAX_INPUT_PIXELS || long_edge > MAX_INPUT_LONG_EDGE) {
        LOGE("Input image too large: %dx%d (%lld pixels, limit %d, max long edge %d)", 
             w, h, pixels, MAX_INPUT_PIXELS, MAX_INPUT_LONG_EDGE);
        stbi_image_free(pixeldata);
        return -9;
    }

    LOGI("Loaded input: %dx%d ch=%d", w, h, c);

    int out_w = w * scale;
    int out_h = h * scale;
    int out_total = out_w * out_h * 3;
    int xtiles = (w + TILE_SIZE - 1) / TILE_SIZE;
    int ytiles = (h + TILE_SIZE - 1) / TILE_SIZE;
    int total_tiles = xtiles * ytiles;
    int done_tiles = 0;

    char phase_buf[128];
    snprintf(phase_buf, sizeof(phase_buf), "Đang chuẩn bị bộ nhớ (%s)", mode_label());
    progress(0, total_tiles, phase_buf, elapsed_ms(start), using_vulkan());

    unsigned char* out_pixeldata = (unsigned char*)calloc(out_total, 1);
    if (!out_pixeldata) {
        LOGE("Failed to allocate output buffer (%d bytes)", out_total);
        stbi_image_free(pixeldata);
        return -8;
    }

    int tile_w = TILE_SIZE + TILE_PAD * 2;
    int tile_h = TILE_SIZE + TILE_PAD * 2;
    unsigned char* tile_in = (unsigned char*)malloc(tile_w * tile_h * 3);
    if (!tile_in) {
        LOGE("Failed to allocate tile input buffer");
        free(out_pixeldata);
        stbi_image_free(pixeldata);
        return -8;
    }

    for (int yi = 0; yi < ytiles; yi++) {
        int tile_y = yi * TILE_SIZE;
        int tile_h_actual = (yi == ytiles - 1) ? (h - tile_y) : TILE_SIZE;

        for (int xi = 0; xi < xtiles; xi++) {
            if (cancel_requested.load()) {
                LOGI("Processing cancelled before tile %d/%d", done_tiles + 1, total_tiles);
                free(tile_in);
                free(out_pixeldata);
                stbi_image_free(pixeldata);
                remove(output_path.c_str());
                return -10;
            }

            int tile_x = xi * TILE_SIZE;
            int tile_w_actual = (xi == xtiles - 1) ? (w - tile_x) : TILE_SIZE;

            snprintf(phase_buf, sizeof(phase_buf), "Đang xử lý tile (%s)", mode_label());
            LOGI("Tile %d,%d: src=(%d,%d) size=%dx%d mode=%s", xi, yi, tile_x, tile_y, tile_w_actual, tile_h_actual, mode_label());
            if (!progress(done_tiles, total_tiles, phase_buf, elapsed_ms(start), using_vulkan())) {
                LOGI("Processing cancelled by callback before tile %d/%d", done_tiles + 1, total_tiles);
                free(tile_in);
                free(out_pixeldata);
                stbi_image_free(pixeldata);
                remove(output_path.c_str());
                return -10;
            }

            auto tile_start = std::chrono::steady_clock::now();
            extract_tile(pixeldata, w, h, tile_x, tile_y, tile_in, tile_w, tile_h, TILE_PAD);

            ncnn::Mat in = ncnn::Mat::from_pixels(tile_in, ncnn::Mat::PIXEL_RGB, tile_w, tile_h);
            const float mean_vals[3] = {0, 0, 0};
            const float norm_vals[3] = {1/255.f, 1/255.f, 1/255.f};
            in.substract_mean_normalize(mean_vals, norm_vals);

            ncnn::Mat tile_out;
            bool used_cpu_fallback = false;

            if (!cpu_fallback_active) {
                // Try primary net (Vulkan or CPU)
                ncnn::Extractor ex = net.create_extractor();
                int ret_input = ex.input("data", in);
                if (ret_input != 0) {
                    LOGE("ex.input(\"data\") failed: %d", ret_input);
                    free(tile_in);
                    free(out_pixeldata);
                    stbi_image_free(pixeldata);
                    return -4;
                }

                int ret_extract = ex.extract("output", tile_out);
                if (ret_extract != 0) {
                    LOGE("ex.extract(\"output\") failed: %d (tile index=%d (x=%d, y=%d), tile_in=%dx%d, padded=%dx%d, img=%dx%d, mode=%s)",
                         ret_extract, done_tiles, xi, yi, tile_w_actual, tile_h_actual, tile_w, tile_h, w, h, mode_label());

                    if (vulkan_enabled) {
                        // Vulkan failed, try CPU fallback for this tile
                        LOGI("Vulkan extract failed, switching to CPU fallback for remaining tiles");
                        cpu_fallback_active = true;

                        int cpu_ret = run_tile_cpu(in, tile_out);
                        if (cpu_ret != 0) {
                            LOGE("CPU fallback also failed: %d", cpu_ret);
                            free(tile_in);
                            free(out_pixeldata);
                            stbi_image_free(pixeldata);
                            return -5;
                        }
                        used_cpu_fallback = true;
                        snprintf(phase_buf, sizeof(phase_buf), "Đang xử lý tile (%s)", mode_label());
                        progress(done_tiles, total_tiles, phase_buf, elapsed_ms(start), false);
                    } else {
                        free(tile_in);
                        free(out_pixeldata);
                        stbi_image_free(pixeldata);
                        return -5;
                    }
                }
            } else {
                // Already in CPU fallback mode
                int cpu_ret = run_tile_cpu(in, tile_out);
                if (cpu_ret != 0) {
                    LOGE("CPU fallback failed: %d (tile index=%d)", cpu_ret, done_tiles);
                    free(tile_in);
                    free(out_pixeldata);
                    stbi_image_free(pixeldata);
                    return -5;
                }
                used_cpu_fallback = true;
            }

            if (tile_out.empty() || tile_out.w <= 0 || tile_out.h <= 0 || tile_out.c <= 0) {
                LOGE("tile output invalid: w=%d h=%d c=%d elemsize=%zu elempack=%d",
                     tile_out.w, tile_out.h, tile_out.c, tile_out.elemsize, tile_out.elempack);
                free(tile_in);
                free(out_pixeldata);
                stbi_image_free(pixeldata);
                return -7;
            }

            if (tile_out.c != 3) {
                LOGE("tile output channels != 3: c=%d w=%d h=%d", tile_out.c, tile_out.w, tile_out.h);
                free(tile_in);
                free(out_pixeldata);
                stbi_image_free(pixeldata);
                return -7;
            }

            LOGI("tile_out: w=%d h=%d c=%d elemsize=%zu elempack=%d %s",
                 tile_out.w, tile_out.h, tile_out.c, tile_out.elemsize, tile_out.elempack,
                 used_cpu_fallback ? "(CPU fallback)" : "");

            // ncnn::Mat uses CHW layout: channel(ch) gives float* for that channel
            const float* ch_r = tile_out.channel(0);
            const float* ch_g = tile_out.channel(1);
            const float* ch_b = tile_out.channel(2);

            int dst_x0 = tile_x * scale;
            int dst_y0 = tile_y * scale;
            int dst_tile_w = tile_w_actual * scale;
            int dst_tile_h = tile_h_actual * scale;
            int pad_scaled = TILE_PAD * scale;

            for (int dy = 0; dy < dst_tile_h; dy++) {
                int src_y = dy + pad_scaled;
                int dst_y = dst_y0 + dy;
                for (int dx = 0; dx < dst_tile_w; dx++) {
                    int src_x = dx + pad_scaled;
                    int dst_x = dst_x0 + dx;
                    int src_idx = src_y * tile_out.w + src_x;
                    int dst_idx = (dst_y * out_w + dst_x) * 3;

                    float vr = ch_r[src_idx] * 255.0f;
                    float vg = ch_g[src_idx] * 255.0f;
                    float vb = ch_b[src_idx] * 255.0f;

                    int ir = (int)(vr + 0.5f); if (ir < 0) ir = 0; if (ir > 255) ir = 255;
                    int ig = (int)(vg + 0.5f); if (ig < 0) ig = 0; if (ig > 255) ig = 255;
                    int ib = (int)(vb + 0.5f); if (ib < 0) ib = 0; if (ib > 255) ib = 255;

                    out_pixeldata[dst_idx + 0] = (unsigned char)ir;
                    out_pixeldata[dst_idx + 1] = (unsigned char)ig;
                    out_pixeldata[dst_idx + 2] = (unsigned char)ib;
                }
            }
            done_tiles++;
            LOGI("Tile %d/%d finished in %lld ms (%s)", done_tiles, total_tiles, elapsed_ms(tile_start), mode_label());
            snprintf(phase_buf, sizeof(phase_buf), "Đang xử lý tile (%s)", mode_label());
            progress(done_tiles, total_tiles, phase_buf, elapsed_ms(start), using_vulkan());
        }
    }

    free(tile_in);
    stbi_image_free(pixeldata);

    progress(total_tiles, total_tiles, "Đang ghi ảnh PNG", elapsed_ms(start), using_vulkan());
    LOGI("Writing output: %dx%d", out_w, out_h);
    auto write_start = std::chrono::steady_clock::now();
    int ret = stbi_write_png(output_path.c_str(), out_w, out_h, 3, out_pixeldata, out_w * 3);
    if (!ret) {
        LOGE("stbi_write_png failed to write: %s", output_path.c_str());
        remove(output_path.c_str());
    }
    LOGI("PNG write finished in %lld ms, total process time %lld ms", elapsed_ms(write_start), elapsed_ms(start));

    free(out_pixeldata);
    return ret ? 0 : -3;
}
