#include <jni.h>
#include <net.h>

#include <memory>

namespace {
constexpr int kInputWidth = 960;
constexpr int kInputHeight = 960;
constexpr int kOutputElements = 6 * 18900;

struct Model { ncnn::Net net; };

jlong create(JNIEnv* env, jstring param_path, jstring bin_path) {
    const char* param = env->GetStringUTFChars(param_path, nullptr);
    const char* bin = env->GetStringUTFChars(bin_path, nullptr);
    auto model = std::make_unique<Model>();
    ncnn::create_gpu_instance();
    if (ncnn::get_gpu_count() == 0) return 0;
    model->net.opt.use_vulkan_compute = true;
    // RC Plus 2 实测:Vulkan 默认 FP16 让召回从 0.985(ONNX FP32)跌到 0.61,
    // 关闭 FP16 走 FP32 计算;速度余量足够(FP16 时 P95=104ms,门槛 200ms)。
    model->net.opt.use_fp16_packed = false;
    model->net.opt.use_fp16_storage = false;
    model->net.opt.use_fp16_arithmetic = false;
    const int param_status = model->net.load_param(param);
    const int bin_status = param_status == 0 ? model->net.load_model(bin) : -1;
    env->ReleaseStringUTFChars(param_path, param);
    env->ReleaseStringUTFChars(bin_path, bin);
    if (param_status != 0 || bin_status != 0) return 0;
    return reinterpret_cast<jlong>(model.release());
}

jfloatArray infer(JNIEnv* env, jlong handle, jobject input) {
    if (handle == 0 || !input) return nullptr;
    auto* bytes = static_cast<float*>(env->GetDirectBufferAddress(input));
    const jlong capacity = env->GetDirectBufferCapacity(input);
    if (!bytes || capacity != 3 * kInputWidth * kInputHeight * sizeof(float)) return nullptr;
    auto* model = reinterpret_cast<Model*>(handle);
    ncnn::Mat tensor(kInputWidth, kInputHeight, 3, bytes);
    auto extractor = model->net.create_extractor();
    if (extractor.input("in0", tensor) != 0) return nullptr;
    ncnn::Mat output;
    if (extractor.extract("out0", output) != 0 || output.total() != kOutputElements) return nullptr;
    auto result = env->NewFloatArray(kOutputElements);
    if (result) env->SetFloatArrayRegion(result, 0, kOutputElements, output);
    return result;
}

void close(jlong handle) { delete reinterpret_cast<Model*>(handle); ncnn::destroy_gpu_instance(); }
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_yinxin_uavfir_benchmark_NcnnBridge_create(JNIEnv* env, jobject, jstring param, jstring bin) {
    return create(env, param, bin);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_yinxin_uavfir_benchmark_NcnnBridge_infer(JNIEnv* env, jobject, jlong handle, jobject input) {
    return infer(env, handle, input);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yinxin_uavfir_benchmark_NcnnBridge_close(JNIEnv*, jobject, jlong handle) {
    close(handle);
}
