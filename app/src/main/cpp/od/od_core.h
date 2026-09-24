#pragma once
#include <cmath>
#include <algorithm>

namespace od {

struct In {
    float a;
    float b;
    float c;
    float d;
    float e;
    float f;
    float g;
    float blend;
    float rRoll;   // rear-tap roll (absolute camera level, radians)
    float rPitch;  // rear-tap pitch (normalized vertical shift)
    float sign;
};

struct Out {
    float lo;
    float hi;
    float span;
    float h0;
    float h1;
    float ctr;
    float bMid;
    float bHalf;
    float t0;
    float t1;
    float k1;
    float k2;
    float rc;
    float rs;
    float lift;
    float vg;
    float rearRc;     // rear-tap roll cos
    float rearRs;     // rear-tap roll sin
    float rearPitch;  // rear-tap pitch
    float rpad;       // reserved (pads uOd4 to a full vec4)
};

static const float K_CLAMP = 1.55334303f;

inline float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

inline Out derive(const In& in) {
    Out o;
    float h0 = std::max(in.a, 0.05f) * 0.5f;
    float raw = (in.b > 0.001f) ? in.b : in.a;
    float h1 = std::max(raw, 0.05f) * 0.5f;
    float ctr = in.sign * std::max(in.c, 0.0f);

    o.h0 = h0;
    o.h1 = h1;
    o.ctr = ctr;
    o.lo = std::min(-h0, ctr - h1);
    o.hi = std::max(h0, ctr + h1);
    o.span = std::max(o.hi - o.lo, 1.0e-4f);

    float ovLo = std::max(-h0, ctr - h1);
    float ovHi = std::min(h0, ctr + h1);
    o.bMid = 0.5f * (ovLo + ovHi);
    float ovHalf = std::max(0.5f * (ovHi - ovLo), 1.0e-4f);
    o.bHalf = std::max(clampf(in.blend, 0.0f, 1.0f) * ovHalf, 1.0e-4f);

    o.t0 = std::max(std::tan(std::max(h0, 0.025f)), 0.001f);
    o.t1 = std::max(std::tan(std::max(h1, 0.025f)), 0.001f);

    o.k1 = in.f;
    o.k2 = in.f * 0.33333f;

    float roll = in.d * in.sign;
    o.rc = std::cos(roll);
    o.rs = std::sin(roll);

    o.lift = in.e;
    o.vg = in.g;

    // Rear-tap rotation. rRoll is an absolute camera level (NOT *sign); the
    // shader's flip-parity handles the per-quadrant mirror, exactly as for the
    // side tap. Defaults rRoll=rPitch=0 -> rearRc=1, rearRs=0, rearPitch=0 =>
    // identity (the rear tap renders bit-exactly as before).
    o.rearRc = std::cos(in.rRoll);
    o.rearRs = std::sin(in.rRoll);
    o.rearPitch = in.rPitch;
    o.rpad = 0.0f;
    return o;
}

}  // namespace od
