#ifndef REALESRGAN_H
#define REALESRGAN_H

#include <string>
#include <vector>
#include "ncnn/net.h"

class RealESRGAN
{
public:
    RealESRGAN();
    ~RealESRGAN();

    int load(const std::string& param_path, const std::string& bin_path);
    int process(const std::string& input_path, const std::string& output_path);

private:
    ncnn::Net net;
    ncnn::Pipeline* realesrgan_preproc;
    ncnn::Pipeline* realesrgan_postproc;
    int scale;
};

#endif // REALESRGAN_H
