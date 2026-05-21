#ifndef REALESRGAN_H
#define REALESRGAN_H

#include <string>
#include <atomic>
#include <functional>
#include "ncnn/net.h"

enum GPUState {
    STATE_VULKAN_PRIMARY = 0,
    STATE_VULKAN_SAFE = 1,
    STATE_VULKAN_SMALL = 2,
    STATE_VULKAN_SMALL_SAFE = 3,
    STATE_VULKAN_SMALLEST = 4,
    STATE_VULKAN_SMALLEST_SAFE = 5,
    STATE_CPU_FALLBACK = 6
};

class RealESRGAN
{
public:
    RealESRGAN();
    ~RealESRGAN();

    int load(const std::string& param_path, const std::string& bin_path);
    int process(
        const std::string& input_path,
        const std::string& output_path,
        int scale,
        const std::function<bool(int, int, const char*, long long, const char*)>& progress,
        const std::atomic_bool& cancel_requested
    );
    bool using_vulkan() const;
    const char* mode_label() const;

private:
    ncnn::Net net;
    ncnn::Net cpu_net;
    int scale;
    bool vulkan_enabled;
    bool cpu_fallback_active;
    bool cpu_net_loaded;
    GPUState current_state;
    std::string cached_param_path;
    std::string cached_bin_path;

    int run_tile_cpu(const ncnn::Mat& in, ncnn::Mat& out);
    int load_cpu_net();
    void apply_gpu_state(GPUState state, int& tile_size);
};

#endif // REALESRGAN_H
