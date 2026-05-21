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

static const int TILE_SIZE_DEFAULT = 128;
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
    current_state = STATE_VULKAN_PRIMARY;

    vulkan_enabled = init_vulkan_once();
    net.opt.use_vulkan_compute = vulkan_enabled;

#if NCNN_VULKAN
    if (vulkan_enabled) {
        net.set_vulkan_device(0);
        int unused_tile_size;
        apply_gpu_state(STATE_VULKAN_PRIMARY, unused_tile_size);
        LOGI("Vulkan enabled, using GPU mode");
        return;
    }
#endif

    current_state = STATE_CPU_FALLBACK;
    net.opt.use_vulkan_compute = false;
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
         elapsed_ms(start), r1, r2, mode_label());
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
    return vulkan_enabled && current_state != STATE_CPU_FALLBACK;
}

const char* RealESRGAN::mode_label() const
{
    if (!vulkan_enabled) return "CPU";
    switch (current_state) {
        case STATE_VULKAN_PRIMARY: return "Vulkan";
        case STATE_VULKAN_SAFE: return "Vulkan safe";
        case STATE_VULKAN_SMALL: return "Vulkan small tile";
        case STATE_VULKAN_SMALL_SAFE: return "Vulkan small safe";
        case STATE_VULKAN_SMALLEST: return "Vulkan smallest tile";
        case STATE_VULKAN_SMALLEST_SAFE: return "Vulkan smallest safe";
        case STATE_CPU_FALLBACK: return "CPU fallback";
        default: return "Unknown";
    }
}

void RealESRGAN::apply_gpu_state(GPUState state, int& tile_size)
{
    current_state = state;
    if (state == STATE_VULKAN_PRIMARY) {
        net.opt.use_fp16_packed = true;
        net.opt.use_fp16_storage = true;
        net.opt.use_fp16_arithmetic = false;
        tile_size = 128;
    } else if (state == STATE_VULKAN_SAFE) {
        net.opt.use_fp16_packed = true;
        net.opt.use_fp16_storage = false;
        net.opt.use_fp16_arithmetic = false;
        tile_size = 128;
    } else if (state == STATE_VULKAN_SMALL) {
        net.opt.use_fp16_packed = true;
        net.opt.use_fp16_storage = true;
        net.opt.use_fp16_arithmetic = false;
        tile_size = 96;
    } else if (state == STATE_VULKAN_SMALL_SAFE) {
        net.opt.use_fp16_packed = true;
        net.opt.use_fp16_storage = false;
        net.opt.use_fp16_arithmetic = false;
        tile_size = 96;
    } else if (state == STATE_VULKAN_SMALLEST) {
        net.opt.use_fp16_packed = true;
        net.opt.use_fp16_storage = true;
        net.opt.use_fp16_arithmetic = false;
        tile_size = 64;
    } else if (state == STATE_VULKAN_SMALLEST_SAFE) {
        net.opt.use_fp16_packed = true;
        net.opt.use_fp16_storage = false;
        net.opt.use_fp16_arithmetic = false;
        tile_size = 64;
    } else if (state == STATE_CPU_FALLBACK) {
        net.opt.use_fp16_packed = false;
        net.opt.use_fp16_storage = false;
        net.opt.use_fp16_arithmetic = false;
        tile_size = 128;
    }
}

static void do_extract_tile(const unsigned char* src, int src_w, int src_h,
                            int tile_x, int tile_y,
                            unsigned char* tile, int tile_w, int tile_h, int pad, int tile_size)
{
    int src_x0 = tile_x - pad;
    int src_y0 = tile_y - pad;
    int src_x1 = tile_x + tile_size + pad;
    int src_y1 = tile_y + tile_size + pad;

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
    int scale,
    const std::function<bool(int, int, const char*, long long, const char*)>& progress,
    const std::atomic_bool& cancel_requested
)
{
    this->scale = scale;
    auto start = std::chrono::steady_clock::now();
    progress(0, 0, "Đang đọc ảnh đầu vào", elapsed_ms(start), mode_label());

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

    LOGI("Loaded input: %dx%d ch=%d mode=%s", w, h, c, mode_label());

    int tile_size = TILE_SIZE_DEFAULT;
    if (vulkan_enabled) {
        apply_gpu_state(STATE_VULKAN_PRIMARY, tile_size);
    } else {
        apply_gpu_state(STATE_CPU_FALLBACK, tile_size);
    }

    int out_w = w * scale;
    int out_h = h * scale;
    int out_total = out_w * out_h * 3;

    char phase_buf[128];
    snprintf(phase_buf, sizeof(phase_buf), "Đang chuẩn bị bộ nhớ (%s)", mode_label());

    unsigned char* out_pixeldata = (unsigned char*)calloc(out_total, 1);
    if (!out_pixeldata) {
        LOGE("Failed to allocate output buffer (%d bytes)", out_total);
        stbi_image_free(pixeldata);
        return -8;
    }

    // Allocate tile buffer for largest possible tile (128 is the maximum tile size)
    int max_tile_size = 128;
    int max_tile_w = max_tile_size + TILE_PAD * 2;
    int max_tile_h = max_tile_size + TILE_PAD * 2;
    unsigned char* tile_in = (unsigned char*)malloc(max_tile_w * max_tile_h * 3);
    if (!tile_in) {
        LOGE("Failed to allocate tile input buffer");
        free(out_pixeldata);
        stbi_image_free(pixeldata);
        return -8;
    }

    int xtiles = (w + tile_size - 1) / tile_size;
    int ytiles = (h + tile_size - 1) / tile_size;
    int total_tiles = xtiles * ytiles;
    int done_tiles = 0;
    long long total_inference_ms = 0;

    LOGI("Tile grid: %dx%d = %d tiles, tile_size=%d, output=%dx%d", xtiles, ytiles, total_tiles, tile_size, out_w, out_h);
    progress(0, total_tiles, phase_buf, elapsed_ms(start), mode_label());

    auto inference_start = std::chrono::steady_clock::now();

    for (int yi = 0; yi < ytiles; yi++) {
        int tile_y = yi * tile_size;
        int tile_h_actual = (yi == ytiles - 1) ? (h - tile_y) : tile_size;

        for (int xi = 0; xi < xtiles; xi++) {
            if (cancel_requested.load()) {
                LOGI("Processing cancelled before tile %d/%d", done_tiles + 1, total_tiles);
                free(tile_in);
                free(out_pixeldata);
                stbi_image_free(pixeldata);
                remove(output_path.c_str());
                return -10;
            }

            int tile_x = xi * tile_size;
            int tile_w_actual = (xi == xtiles - 1) ? (w - tile_x) : tile_size;

            int cur_tile_w = tile_size + TILE_PAD * 2;
            int cur_tile_h = tile_size + TILE_PAD * 2;

            snprintf(phase_buf, sizeof(phase_buf), "Đang xử lý tile (%s)", mode_label());
            LOGI("Tile %d,%d: src=(%d,%d) size=%dx%d tile_size=%d mode=%s",
                 xi, yi, tile_x, tile_y, tile_w_actual, tile_h_actual, tile_size, mode_label());
            if (!progress(done_tiles, total_tiles, phase_buf, elapsed_ms(start), mode_label())) {
                LOGI("Processing cancelled by callback before tile %d/%d", done_tiles + 1, total_tiles);
                free(tile_in);
                free(out_pixeldata);
                stbi_image_free(pixeldata);
                remove(output_path.c_str());
                return -10;
            }

            auto tile_start = std::chrono::steady_clock::now();
            do_extract_tile(pixeldata, w, h, tile_x, tile_y, tile_in, cur_tile_w, cur_tile_h, TILE_PAD, tile_size);

            ncnn::Mat tile_out;
            bool success = false;

            while (!success) {
                ncnn::Mat in = ncnn::Mat::from_pixels(tile_in, ncnn::Mat::PIXEL_RGB, cur_tile_w, cur_tile_h);
                const float mean_vals[3] = {0, 0, 0};
                const float norm_vals[3] = {1/255.f, 1/255.f, 1/255.f};
                in.substract_mean_normalize(mean_vals, norm_vals);

                if (current_state == STATE_CPU_FALLBACK) {
                    int cpu_ret = run_tile_cpu(in, tile_out);
                    if (cpu_ret == 0) {
                        success = true;
                    } else {
                        LOGE("CPU fallback failed: %d", cpu_ret);
                        free(tile_in);
                        free(out_pixeldata);
                        stbi_image_free(pixeldata);
                        return -5;
                    }
                } else {
                    ncnn::Extractor ex = net.create_extractor();
                    int ret_input = ex.input("data", in);
                    int ret_extract = -1;
                    if (ret_input == 0) {
                        ret_extract = ex.extract("output", tile_out);
                    }

                    if (ret_extract == 0) {
                        success = true;
                    } else {
                        LOGE("Vulkan failed at state %s (ret_input=%d, ret_extract=%d, tile=%d,%d)", 
                             mode_label(), ret_input, ret_extract, xi, yi);
                        
                        if (current_state == STATE_VULKAN_PRIMARY) {
                            LOGI("Downgrading Vulkan: PRIMARY -> SAFE");
                            apply_gpu_state(STATE_VULKAN_SAFE, tile_size);
                            continue;
                        } else if (current_state == STATE_VULKAN_SAFE) {
                            LOGI("Downgrading Vulkan: SAFE -> SMALL (requires restart)");
                            apply_gpu_state(STATE_VULKAN_SMALL, tile_size);

                            xtiles = (w + tile_size - 1) / tile_size;
                            ytiles = (h + tile_size - 1) / tile_size;
                            total_tiles = xtiles * ytiles;
                            done_tiles = 0;
                            total_inference_ms = 0;
                            memset(out_pixeldata, 0, out_total);

                            snprintf(phase_buf, sizeof(phase_buf), "Thử lại với tile nhỏ (%s)", mode_label());
                            progress(0, total_tiles, phase_buf, elapsed_ms(start), mode_label());

                            yi = -1;
                            break;
                        } else if (current_state == STATE_VULKAN_SMALL) {
                            LOGI("Downgrading Vulkan: SMALL -> SMALL_SAFE");
                            apply_gpu_state(STATE_VULKAN_SMALL_SAFE, tile_size);
                            continue;
                        } else if (current_state == STATE_VULKAN_SMALL_SAFE) {
                            LOGI("Downgrading Vulkan: SMALL_SAFE -> SMALLEST (requires restart)");
                            apply_gpu_state(STATE_VULKAN_SMALLEST, tile_size);

                            xtiles = (w + tile_size - 1) / tile_size;
                            ytiles = (h + tile_size - 1) / tile_size;
                            total_tiles = xtiles * ytiles;
                            done_tiles = 0;
                            total_inference_ms = 0;
                            memset(out_pixeldata, 0, out_total);

                            snprintf(phase_buf, sizeof(phase_buf), "Thử lại với tile nhỏ nhất (%s)", mode_label());
                            progress(0, total_tiles, phase_buf, elapsed_ms(start), mode_label());

                            yi = -1;
                            break;
                        } else if (current_state == STATE_VULKAN_SMALLEST) {
                            LOGI("Downgrading Vulkan: SMALLEST -> SMALLEST_SAFE");
                            apply_gpu_state(STATE_VULKAN_SMALLEST_SAFE, tile_size);
                            continue;
                        } else if (current_state == STATE_VULKAN_SMALLEST_SAFE) {
                            LOGI("Downgrading Vulkan: SMALLEST_SAFE -> CPU_FALLBACK");
                            apply_gpu_state(STATE_CPU_FALLBACK, tile_size);
                            continue;
                        }
                    }
                }
            }

            if (yi < 0) break; // broke out of inner loop to restart

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
            long long tile_ms = elapsed_ms(tile_start);
            total_inference_ms += tile_ms;
            LOGI("Tile %d/%d finished in %lld ms (%s)", done_tiles, total_tiles, tile_ms, mode_label());
            snprintf(phase_buf, sizeof(phase_buf), "Đang xử lý tile (%s)", mode_label());
            progress(done_tiles, total_tiles, phase_buf, elapsed_ms(start), mode_label());
        }
    }

    long long inference_total = elapsed_ms(inference_start);
    long long avg_tile = total_tiles > 0 ? total_inference_ms / total_tiles : 0;
    LOGI("=== INFERENCE SUMMARY: %d tiles, total=%lld ms, avg=%lld ms/tile, tile_size=%d, mode=%s ===",
         total_tiles, inference_total, avg_tile, tile_size, mode_label());

    free(tile_in);
    stbi_image_free(pixeldata);

    progress(total_tiles, total_tiles, "Đang ghi ảnh PNG", elapsed_ms(start), mode_label());
    LOGI("Writing output: %dx%d", out_w, out_h);
    auto write_start = std::chrono::steady_clock::now();
    int ret = stbi_write_png(output_path.c_str(), out_w, out_h, 3, out_pixeldata, out_w * 3);
    if (!ret) {
        LOGE("stbi_write_png failed to write: %s", output_path.c_str());
        remove(output_path.c_str());
    }
    long long png_ms = elapsed_ms(write_start);
    long long total_ms = elapsed_ms(start);
    LOGI("=== TOTAL SUMMARY: inference=%lld ms, png_write=%lld ms, total=%lld ms, mode=%s ===",
         inference_total, png_ms, total_ms, mode_label());

    free(out_pixeldata);
    return ret ? 0 : -3;
}
