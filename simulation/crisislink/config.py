from dataclasses import dataclass


@dataclass(frozen=True)
class SimConfig:
    """World, traffic and radio parameters. Times are in seconds."""

    n_nodes: int = 50
    area: float = 2500.0          # square side, metres
    comm_range: float = 40.0      # BLE contact range, metres
    dt: float = 10.0              # simulation step
    duration: float = 6 * 3600.0
    ttl: float = 2 * 3600.0       # message lifetime; generation stops duration-ttl before the end

    # clustered random-waypoint mobility
    n_zones: int = 5              # shelters / aid points
    zone_radius: float = 150.0
    zone_bias: float = 0.85       # P(next waypoint is inside a zone)
    home_bias: float = 0.70       # P(zone is the node's home zone | in a zone)
    speed_min: float = 0.5
    speed_max: float = 1.5
    pause_max: float = 600.0

    # traffic
    msg_interval: float = 1800.0  # mean seconds between messages per node
    urgent_frac: float = 0.10

    # radio / resources
    buffer_size: int = 12
    link_budget: int = 2          # messages per direction per step: low duty-cycle BLE
    max_hops: int = 10
    battery_idle: float = 0.15 / (6 * 3600.0)   # fraction drained per second while idle
    battery_tx: float = 0.0010    # fraction per message sent
    battery_rx: float = 0.0006    # fraction per message received

    # PRoPHET-style encounter predictability (shared by the DQN state and the PRoPHET baseline)
    p_init: float = 0.75
    p_beta: float = 0.25
    p_gamma: float = 0.98         # aging factor per p_unit
    p_unit: float = 600.0

    spray_copies: int = 8


@dataclass(frozen=True)
class RewardConfig:
    tx_cost: float = 0.01         # per transmission
    deliver: float = 1.0          # delivery reward (normal priority)
    urgent_mult: float = 2.0
    latency_floor: float = 0.5    # reward scales between floor and 1 with remaining TTL
    evict_penalty: float = 0.10   # urgent message displacing a queued one
    copy_credit: float = 0.7      # weight of the new copy's future value in the forward target
