import gym
import os

from gym.wrappers.resize_observation import ResizeObservation
from gym.wrappers.gray_scale_observation import GrayScaleObservation
from gym.wrappers.frame_stack import FrameStack
from stable_baselines3.common.atari_wrappers import (
    MaxAndSkipEnv,
    NoopResetEnv,
)
from stable_baselines3.common.vec_env.base_vec_env import VecEnv
from stable_baselines3.common.vec_env.dummy_vec_env import DummyVecEnv
from stable_baselines3.common.vec_env.subproc_vec_env import SubprocVecEnv
from stable_baselines3.common.vec_env.vec_normalize import VecNormalize
from torch.utils.tensorboard.writer import SummaryWriter
from typing import Any, Callable, Dict, Optional

from shared.policy.policy import VEC_NORMALIZE_FILENAME
from wrappers.atari_wrappers import EpisodicLifeEnv, FireOnLifeStarttEnv, ClipRewardEnv
from wrappers.episode_stats_writer import EpisodeStatsWriter


def make_env(
    env_id: str,
    seed: Optional[int],
    training: bool = True,
    render: bool = False,
    normalize_load_path: Optional[str] = None,
    n_envs: int = 1,
    frame_stack: int = 1,
    make_kwargs: Optional[Dict[str, Any]] = None,
    no_reward_timeout_steps: Optional[int] = None,
    vec_env_class: str = "dummy",
    normalize: bool = False,
    normalize_kwargs: Optional[Dict[str, Any]] = None,
    tb_writer: Optional[SummaryWriter] = None,
    rolling_length: int = 100,
) -> VecEnv:
    if "BulletEnv" in env_id:
        import pybullet_envs

    make_kwargs = make_kwargs if make_kwargs is not None else {}
    if "BulletEnv" in env_id and render:
        make_kwargs["render"] = True
    if "CarRacing" in env_id:
        make_kwargs["verbose"] = 0

    spec = gym.spec(env_id)

    def make(idx: int) -> Callable[[], gym.Env]:
        def _make() -> gym.Env:
            env = gym.make(env_id, **make_kwargs)
            env = gym.wrappers.RecordEpisodeStatistics(env)
            if "AtariEnv" in spec.entry_point:  # type: ignore
                env = NoopResetEnv(env, noop_max=30)
                env = MaxAndSkipEnv(env, skip=4)
                env = EpisodicLifeEnv(env, training=training)
                action_meanings = env.unwrapped.get_action_meanings()
                if "FIRE" in action_meanings:  # type: ignore
                    env = FireOnLifeStarttEnv(env, action_meanings.index("FIRE"))
                env = ClipRewardEnv(env, training=training)
                env = ResizeObservation(env, (84, 84))
                env = GrayScaleObservation(env, keep_dim=False)
                env = FrameStack(env, frame_stack)
            elif "CarRacing" in env_id:
                env = ResizeObservation(env, (64, 64))
                env = GrayScaleObservation(env, keep_dim=False)
                env = FrameStack(env, frame_stack)

            if no_reward_timeout_steps:
                from wrappers.no_reward_timeout import NoRewardTimeout

                env = NoRewardTimeout(env, no_reward_timeout_steps)

            if seed is not None:
                env.seed(seed + idx)
                env.action_space.seed(seed + idx)
                env.observation_space.seed(seed + idx)

            return env

        return _make

    VecEnvClass = {"dummy": DummyVecEnv, "subproc": SubprocVecEnv}[vec_env_class]
    venv = VecEnvClass([make(i) for i in range(n_envs)])
    if training:
        assert tb_writer
        venv = EpisodeStatsWriter(
            venv, tb_writer, training=training, rolling_length=rolling_length
        )
    if normalize:
        if normalize_load_path:
            venv = VecNormalize.load(
                os.path.join(normalize_load_path, VEC_NORMALIZE_FILENAME), venv
            )
        else:
            venv = VecNormalize(venv, training=training, **(normalize_kwargs or {}))
        if not training:
            venv.norm_reward = False
    return venv