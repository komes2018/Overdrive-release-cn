// od.cpp — native helper (JNI surface).
#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include "od_core.h"

namespace {

constexpr uint64_t HX_A = 0xc4469339686b54b0ULL ^ 0x5A5A5A5A5A5A5A5AULL;
constexpr uint64_t HX_B = 0x696e62764586213eULL ^ 0xA5A5A5A5A5A5A5A5ULL;

inline uint64_t kA() { return HX_A ^ 0x5A5A5A5A5A5A5A5AULL; }
inline uint64_t kB() { return HX_B ^ 0xA5A5A5A5A5A5A5A5ULL; }

bool g_ok = false;

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_overdrive_app_od_Od_nativeAuthorize(JNIEnv* env, jclass,
                                             jlong a, jlong b) {
    uint64_t hi = static_cast<uint64_t>(a);
    uint64_t lo = static_cast<uint64_t>(b);
    g_ok = (hi == kA() && lo == kB());
    return g_ok ? 1 : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_overdrive_app_od_Od_nativeResolve(JNIEnv* env, jclass,
                                           jfloatArray jin, jfloatArray jout) {
    if (jin == nullptr || jout == nullptr) return 0;
    if (env->GetArrayLength(jin) < 11 || env->GetArrayLength(jout) < 20) return 0;

    jfloat ib[11];
    env->GetFloatArrayRegion(jin, 0, 11, ib);

    bool ok = g_ok;
#ifndef NDEBUG
    // Development builds: NDEBUG is undefined only on the Debug variant, so this
    // entire branch is removed from the optimized (release) binary by the
    // preprocessor — the shipped library has no path here.
    ok = true;
#endif

    od::Out o{};
    if (ok) {
        od::In in{};
        in.a = ib[0]; in.b = ib[1]; in.c = ib[2]; in.d = ib[3];
        in.e = ib[4]; in.f = ib[5]; in.g = ib[6]; in.blend = ib[7];
        in.rRoll = ib[8]; in.rPitch = ib[9];
        in.sign = ib[10];
        o = od::derive(in);
    }

    jfloat ob[20] = {
        o.lo, o.hi, o.span, o.h0, o.h1, o.ctr, o.bMid, o.bHalf,
        o.t0, o.t1, o.k1, o.k2, o.rc, o.rs, o.lift, o.vg,
        o.rearRc, o.rearRs, o.rearPitch, o.rpad,
    };
    env->SetFloatArrayRegion(jout, 0, 20, ob);
    return ok ? 1 : 0;
}
