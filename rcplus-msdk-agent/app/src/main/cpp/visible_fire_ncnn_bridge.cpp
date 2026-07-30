#include <jni.h>
#include <net.h>

#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <utility>

namespace {
constexpr int kInputWidth = 960;
constexpr int kInputHeight = 960;
constexpr int kOutputChannels = 6;
constexpr int kCandidateCount = 18900;
constexpr int kOutputElements = kOutputChannels * kCandidateCount;

struct Model {
    ncnn::Net net;
};

std::mutex g_runtime_mutex;
std::unordered_map<uint64_t, std::unique_ptr<Model>> g_active_models;
uint64_t g_next_model_token = 1;
int g_gpu_session_count = 0;

bool allocate_model_token_locked(uint64_t* token) {
    if (g_next_model_token == 0) return false;
    *token = g_next_model_token++;
    return true;
}

jlong handle_from_token(uint64_t token) {
    static_assert(sizeof(jlong) == sizeof(uint64_t), "JNI handle width changed");
    jlong handle = 0;
    std::memcpy(&handle, &token, sizeof(handle));
    return handle;
}

uint64_t token_from_handle(jlong handle) {
    static_assert(sizeof(jlong) == sizeof(uint64_t), "JNI handle width changed");
    uint64_t token = 0;
    std::memcpy(&token, &handle, sizeof(token));
    return token;
}

bool acquire_gpu_session_locked() {
    if (g_gpu_session_count == 0) {
        ncnn::create_gpu_instance();
        if (ncnn::get_gpu_count() == 0) {
            ncnn::destroy_gpu_instance();
            return false;
        }
    }
    ++g_gpu_session_count;
    return true;
}

void release_gpu_session_locked() {
    if (g_gpu_session_count <= 0) return;
    --g_gpu_session_count;
    if (g_gpu_session_count == 0) ncnn::destroy_gpu_instance();
}

bool has_expected_output_shape(const ncnn::Mat& output) {
    return output.dims == 2 &&
           output.w == kCandidateCount &&
           output.h == kOutputChannels;
}

jlong create(JNIEnv* env, jstring param_path, jstring bin_path) {
    const char* param = env->GetStringUTFChars(param_path, nullptr);
    const char* bin = env->GetStringUTFChars(bin_path, nullptr);
    if (!param || !bin) {
        if (param) env->ReleaseStringUTFChars(param_path, param);
        if (bin) env->ReleaseStringUTFChars(bin_path, bin);
        return 0;
    }
    auto model = std::make_unique<Model>();
    int param_status = -1;
    int bin_status = -1;
    uint64_t token = 0;
    bool published = false;
    {
        std::lock_guard<std::mutex> lock(g_runtime_mutex);
        if (acquire_gpu_session_locked()) {
            model->net.opt.use_vulkan_compute = true;
            // RC Plus 2 provisional evidence showed unacceptable recall loss with Vulkan FP16.
            model->net.opt.use_fp16_packed = false;
            model->net.opt.use_fp16_storage = false;
            model->net.opt.use_fp16_arithmetic = false;
            param_status = model->net.load_param(param);
            bin_status = param_status == 0 ? model->net.load_model(bin) : -1;
            if (param_status == 0 &&
                bin_status == 0 &&
                allocate_model_token_locked(&token)) {
                published =
                    g_active_models.emplace(token, std::move(model)).second;
            }
            if (!published) {
                model.reset();
                release_gpu_session_locked();
            }
        }
    }
    env->ReleaseStringUTFChars(param_path, param);
    env->ReleaseStringUTFChars(bin_path, bin);
    if (!published) return 0;
    return handle_from_token(token);
}

jfloatArray infer(JNIEnv* env, jlong handle, jobject input) {
    if (handle == 0 || !input) return nullptr;
    std::lock_guard<std::mutex> lock(g_runtime_mutex);
    const auto active = g_active_models.find(token_from_handle(handle));
    if (active == g_active_models.end()) return nullptr;
    auto* model = active->second.get();
    auto* bytes = static_cast<float*>(env->GetDirectBufferAddress(input));
    const jlong capacity = env->GetDirectBufferCapacity(input);
    if (!bytes || capacity != 3 * kInputWidth * kInputHeight * sizeof(float)) return nullptr;
    ncnn::Mat tensor(kInputWidth, kInputHeight, 3, bytes);
    auto extractor = model->net.create_extractor();
    if (extractor.input("in0", tensor) != 0) return nullptr;
    ncnn::Mat output;
    if (extractor.extract("out0", output) != 0 || !has_expected_output_shape(output)) {
        return nullptr;
    }
    auto result = env->NewFloatArray(kOutputElements);
    if (result) env->SetFloatArrayRegion(result, 0, kOutputElements, output);
    return result;
}

void close(jlong handle) {
    if (handle == 0) return;
    std::lock_guard<std::mutex> lock(g_runtime_mutex);
    const auto active = g_active_models.find(token_from_handle(handle));
    if (active == g_active_models.end()) return;
    std::unique_ptr<Model> model = std::move(active->second);
    g_active_models.erase(active);
    model.reset();
    release_gpu_session_locked();
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_yinxin_uavfir_firedetection_VisibleFireNcnnBridge_create(
    JNIEnv* env,
    jobject,
    jstring param,
    jstring bin) {
    return create(env, param, bin);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_yinxin_uavfir_firedetection_VisibleFireNcnnBridge_infer(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject input) {
    return infer(env, handle, input);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yinxin_uavfir_firedetection_VisibleFireNcnnBridge_close(
    JNIEnv*,
    jobject,
    jlong handle) {
    close(handle);
}
