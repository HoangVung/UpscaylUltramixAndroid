#include "realesrgan.h"
#include <android/log.h>

#define STB_IMAGE_IMPLEMENTATION
#define STBI_NO_THREAD_LOCALS
#include "stb_image.h"

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

#define LOG_TAG "RealESRGAN"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

RealESRGAN::RealESRGAN()
{
    net.opt.use_vulkan_compute = true;
    net.opt.use_fp16_packed = true;
    net.opt.use_fp16_storage = true;
    net.opt.use_fp16_arithmetic = true;
    scale = 4;
}

RealESRGAN::~RealESRGAN()
{
    net.clear();
}

int RealESRGAN::load(const std::string& param_path, const std::string& bin_path)
{
    int r1 = net.load_param(param_path.c_str());
    int r2 = net.load_model(bin_path.c_str());
    if (r1 != 0 || r2 != 0) return -1;
    return 0;
}

int RealESRGAN::process(const std::string& input_path, const std::string& output_path)
{
    int w, h, c;
    unsigned char* pixeldata = stbi_load(input_path.c_str(), &w, &h, &c, 3);
    if (!pixeldata) return -2;

    ncnn::Mat in = ncnn::Mat::from_pixels(pixeldata, ncnn::Mat::PIXEL_RGB, w, h);

    // Chuẩn hóa nếu cần (Real-ESRGAN thường cần [0, 1])
    const float mean_vals[3] = {0, 0, 0};
    const float norm_vals[3] = {1/255.f, 1/255.f, 1/255.f};
    in.substract_mean_normalize(mean_vals, norm_vals);

    ncnn::Extractor ex = net.create_extractor();
    ex.input("input", in);

    ncnn::Mat out;
    ex.extract("output", out);

    // Chuyển ngược về pixel
    float denorm_vals[3] = {255.f, 255.f, 255.f};
    // ncnn không có trực tiếp denormalize trong từ pixels, ta làm tay hoặc dùng out.to_pixels
    // Nhưng trước đó cần đưa về dải 0-255

    unsigned char* out_pixeldata = (unsigned char*)malloc(out.w * out.h * 3);
    out.to_pixels(out_pixeldata, ncnn::Mat::PIXEL_RGB);

    int ret = stbi_write_png(output_path.c_str(), out.w, out.h, 3, out_pixeldata, out.w * 3);

    stbi_image_free(pixeldata);
    free(out_pixeldata);

    return ret ? 0 : -3;
}
