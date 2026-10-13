#pragma once

#include <cstdint>
#include <limits>

namespace pocketpc::vulkan_continuous_present {

// Kotlin/JNI transports timeline values as signed Long/jlong. Keep every
// cross-language value positive and fail closed before the sign bit.
constexpr uint64_t kBridgeMaximumTimelineValue =
    static_cast<uint64_t>(std::numeric_limits<int64_t>::max());
constexpr uint64_t kFirstFrameSequence = 1u;
constexpr uint64_t kMaximumFrameSequence = kBridgeMaximumTimelineValue / 2u;
constexpr uint64_t kMaximumGuestReadyValue = kBridgeMaximumTimelineValue - 2u;
constexpr uint64_t kMaximumHostConsumedValue = kBridgeMaximumTimelineValue - 1u;

constexpr bool IsFrameSequenceValid(uint64_t frame_sequence) {
    return frame_sequence >= kFirstFrameSequence &&
        frame_sequence <= kMaximumFrameSequence;
}

constexpr uint64_t GuestReadyValue(uint64_t frame_sequence) {
    return IsFrameSequenceValid(frame_sequence)
        ? frame_sequence * 2u - 1u
        : 0u;
}

constexpr uint64_t HostConsumedValue(uint64_t frame_sequence) {
    return IsFrameSequenceValid(frame_sequence)
        ? frame_sequence * 2u
        : 0u;
}

constexpr uint64_t PreviousHostConsumedValue(uint64_t frame_sequence) {
    if (!IsFrameSequenceValid(frame_sequence)) return 0u;
    return frame_sequence == kFirstFrameSequence
        ? 0u
        : HostConsumedValue(frame_sequence - 1u);
}

constexpr bool IsGuestReadyValue(uint64_t value) {
    return value >= 1u &&
        value <= kMaximumGuestReadyValue &&
        (value & 1u) == 1u;
}

constexpr bool IsHostConsumedValue(uint64_t value) {
    return value == 0u ||
        (value <= kMaximumHostConsumedValue && (value & 1u) == 0u);
}

constexpr uint64_t FrameSequenceFromGuestReady(uint64_t value) {
    return IsGuestReadyValue(value) ? (value / 2u) + 1u : 0u;
}

constexpr uint64_t FrameSequenceFromHostConsumed(uint64_t value) {
    return value != 0u && IsHostConsumedValue(value) ? value / 2u : 0u;
}

constexpr bool IsExactOwnershipPair(
        uint64_t frame_sequence,
        uint64_t guest_ready,
        uint64_t host_consumed) {
    return IsFrameSequenceValid(frame_sequence) &&
        guest_ready == GuestReadyValue(frame_sequence) &&
        host_consumed == HostConsumedValue(frame_sequence) &&
        host_consumed == guest_ready + 1u;
}

constexpr bool CanGuestAcquire(
        uint64_t frame_sequence,
        uint64_t observed_timeline_value) {
    return IsFrameSequenceValid(frame_sequence) &&
        observed_timeline_value == PreviousHostConsumedValue(frame_sequence);
}

static_assert(kBridgeMaximumTimelineValue == 9223372036854775807ull);
static_assert(kMaximumFrameSequence == 4611686018427387903ull);
static_assert(GuestReadyValue(1u) == 1u);
static_assert(HostConsumedValue(1u) == 2u);
static_assert(GuestReadyValue(2u) == 3u);
static_assert(HostConsumedValue(2u) == 4u);
static_assert(GuestReadyValue(kMaximumFrameSequence) ==
    kMaximumGuestReadyValue);
static_assert(HostConsumedValue(kMaximumFrameSequence) ==
    kMaximumHostConsumedValue);
static_assert(FrameSequenceFromGuestReady(1u) == 1u);
static_assert(FrameSequenceFromGuestReady(3u) == 2u);
static_assert(FrameSequenceFromGuestReady(2u) == 0u);
static_assert(FrameSequenceFromHostConsumed(2u) == 1u);
static_assert(FrameSequenceFromHostConsumed(4u) == 2u);
static_assert(FrameSequenceFromHostConsumed(3u) == 0u);
static_assert(IsExactOwnershipPair(1u, 1u, 2u));
static_assert(IsExactOwnershipPair(2u, 3u, 4u));
static_assert(!IsExactOwnershipPair(2u, 1u, 2u));
static_assert(CanGuestAcquire(1u, 0u));
static_assert(CanGuestAcquire(2u, 2u));
static_assert(!CanGuestAcquire(2u, 3u));
static_assert(!IsFrameSequenceValid(0u));
static_assert(!IsFrameSequenceValid(kMaximumFrameSequence + 1u));

}  // namespace pocketpc::vulkan_continuous_present
