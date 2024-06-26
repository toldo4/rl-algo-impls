from typing import Optional, Tuple, TypeVar, Union

import numpy as np
from numpy.typing import NDArray

from rl_algo_impls.utils.running_mean_std import HybridMovingMeanVar, RunningMeanStd
from rl_algo_impls.wrappers.vector_wrapper import (
    VecEnvMaskedResetReturn,
    VectorEnv,
    VectorWrapper,
)

NormalizeObservationSelf = TypeVar(
    "NormalizeObservationSelf", bound="NormalizeObservation"
)


class NormalizeObservation(VectorWrapper):
    def __init__(
        self,
        env: VectorEnv,
        training: bool = True,
        epsilon: float = 1e-8,
        clip: float = 10.0,
    ) -> None:
        super().__init__(env)
        self.rms = RunningMeanStd(shape=env.single_observation_space.shape)
        self.training = training
        self.epsilon = epsilon
        self.clip = clip

    def step(self, action):
        obs, reward, terminations, truncations, info = self.env.step(action)
        return self.normalize(obs), reward, terminations, truncations, info

    def reset(self, **kwargs):
        obs, info = self.env.reset(**kwargs)
        return self.normalize(obs), info

    def normalize(self, obs: NDArray) -> NDArray:
        if self.training:
            self.rms.update(obs)
        normalized = np.clip(
            (obs - self.rms.mean) / np.sqrt(self.rms.var + self.epsilon),
            -self.clip,
            self.clip,
        )
        return normalized

    def save(self, path: str) -> None:
        self.rms.save(path)

    def load(self, path: str) -> None:
        self.rms.load(path)

    def load_from(
        self: NormalizeObservationSelf, existing: NormalizeObservationSelf
    ) -> None:
        self.rms.load_from(existing.rms)


NormalizeRewardSelf = TypeVar("NormalizeRewardSelf", bound="NormalizeReward")


class NormalizeReward(VectorWrapper):
    def __init__(
        self,
        env: VectorEnv,
        training: bool = True,
        gamma: float = 0.99,
        epsilon: float = 1e-8,
        clip: float = 10.0,
        shape: Tuple[int, ...] = (),
        exponential_moving_mean_var: bool = False,
        emv_window_size: Optional[Union[int, float]] = None,
    ) -> None:
        super().__init__(env)
        self.rms = (
            HybridMovingMeanVar(window_size=emv_window_size, shape=shape)
            if exponential_moving_mean_var
            else RunningMeanStd(shape=shape)
        )
        self.training = training
        self.gamma = gamma
        self.epsilon = epsilon
        self.clip = clip

        self.returns = np.zeros((self.num_envs,) + shape)

    def step(self, action):
        obs, reward, terminations, truncations, info = self.env.step(action)

        reward = self.normalize(reward)

        self.returns[terminations | truncations] = 0

        return obs, reward, terminations, truncations, info

    def reset(self, **kwargs):
        self.returns = np.zeros_like(self.returns)
        return self.env.reset(**kwargs)

    def masked_reset(self, env_mask: np.ndarray) -> VecEnvMaskedResetReturn:
        self.returns[env_mask] = 0
        return self.env.masked_reset(env_mask)  # type: ignore

    def normalize(self, rewards):
        if self.training:
            self.returns = self.returns * self.gamma + rewards
            self.rms.update(self.returns)
        return np.clip(
            rewards / np.sqrt(self.rms.var + self.epsilon), -self.clip, self.clip
        )

    def save(self, path: str) -> None:
        self.rms.save(path)

    def load(self, path: str) -> None:
        self.rms.load(path)

    def load_from(self: NormalizeRewardSelf, existing: NormalizeRewardSelf) -> None:
        self.rms.load_from(existing.rms)
