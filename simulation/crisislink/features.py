"""State features for one forwarding decision: (message at node a, candidate peer b).

Every feature is in [0, 1] and is observable by node `a` from its own state plus the
peer's handshake summary. The same order and definitions are reimplemented in
core/src/main/kotlin/org/crisislink/core/routing/FeatureBuilder.kt; ParityTest.kt pins them together.
"""

FEATURE_NAMES = [
    "urgent",            # message priority
    "ttl_left",          # remaining lifetime / ttl
    "hops",              # hops travelled / max_hops
    "p_a_dst",           # a's encounter predictability for the destination
    "p_b_dst",           # b's encounter predictability for the destination
    "p_delta",           # (p_b_dst - p_a_dst + 1) / 2
    "b_free",            # b's free buffer fraction
    "a_free",            # a's free buffer fraction
    "b_battery",
    "a_battery",
    "link_quality",      # 1 - distance/range (RSSI in the app)
    "contact_age",       # time in contact / 5 min, capped
    "neighbours",        # peers currently in range / 5, capped
    "is_source",         # a created the message
    "b_is_dst",
    "copies_made",       # copies a has already handed out / spray_copies
]
NUM_FEATURES = len(FEATURE_NAMES)

CONTACT_AGE_CAP_S = 300.0
NEIGHBOUR_CAP = 5.0
COPIES_CAP = 8.0
