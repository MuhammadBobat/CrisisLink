"""Export a trained QNet as JSON for the on-device Dart policy, plus parity vectors."""
import json
import os

import numpy as np
import torch.nn as nn

from .features import FEATURE_NAMES, NUM_FEATURES

POLICY_VERSION = 1


def export_policy(qnet, path: str, meta: dict | None = None, n_parity: int = 24):
    layers = []
    modules = list(qnet.net)
    for i, mod in enumerate(modules):
        if isinstance(mod, nn.Linear):
            act = "relu" if i + 1 < len(modules) and isinstance(modules[i + 1], nn.ReLU) else "linear"
            layers.append({
                "w": mod.weight.detach().numpy().T.round(6).tolist(),   # [in][out]
                "b": mod.bias.detach().numpy().round(6).tolist(),
                "act": act,
            })
    rng = np.random.default_rng(123)
    xs = rng.random((n_parity, NUM_FEATURES)).astype(np.float32)
    # exported weights are rounded, so record the outputs of the rounded network
    ys = forward_json(layers, xs)
    doc = {
        "version": POLICY_VERSION,
        "feature_names": FEATURE_NAMES,
        "actions": ["hold", "forward"],
        "layers": layers,
        "meta": meta or {},
        "parity": {"inputs": xs.round(6).tolist(), "outputs": ys.round(6).tolist()},
    }
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w") as f:
        json.dump(doc, f, separators=(",", ":"))
    return doc


def forward_json(layers, xs: np.ndarray) -> np.ndarray:
    h = np.asarray(xs, np.float64)
    for layer in layers:
        h = h @ np.array(layer["w"]) + np.array(layer["b"])
        if layer["act"] == "relu":
            h = np.maximum(h, 0)
    return h
