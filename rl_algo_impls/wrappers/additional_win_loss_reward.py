from typing import Optional

import numpy as np

from rl_algo_impls.wrappers.vector_wrapper import (
    VecEnvStepReturn,
    VectorEnv,
    VectorWrapper,
    get_infos,
)


class AdditionalWinLossRewardWrapper(VectorWrapper):
    def __init__(
        self, env: VectorEnv, label_smoothing_factor: Optional[float] = None
    ) -> None:
        super().__init__(env)
        self.label_smoothing_factor = label_smoothing_factor

    def step(self, action) -> VecEnvStepReturn:
        o, r, te, tr, infos = super().step(action)
        winloss = np.array(
            [
                results.get("WinLoss", 0)
                for results in get_infos(infos, "results", self.num_envs, {})
            ],
            dtype=np.float32,
        )

        if self.label_smoothing_factor is not None:
            winloss *= self.label_smoothing_factor
        if len(r.shape) == 1:
            r = np.expand_dims(r, axis=-1)
        rewards = np.concatenate([r, np.expand_dims(winloss, axis=-1)], axis=-1)
        return o, rewards, te, tr, infos
