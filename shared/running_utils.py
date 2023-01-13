import gym
import matplotlib.pyplot as plt
import os
import torch
import yaml

from dataclasses import dataclass
from datetime import datetime
from stable_baselines3.common.vec_env.base_vec_env import VecEnv
from stable_baselines3.common.vec_env.dummy_vec_env import DummyVecEnv
from stable_baselines3.common.vec_env.subproc_vec_env import SubprocVecEnv
from typing import Any, Dict, List, Optional, Type, TypedDict, Union

from shared.algorithm import Algorithm
from shared.callbacks.eval_callback import EvalCallback
from shared.policy import Policy
from shared.stats import EpisodesStats

from dqn.dqn import DQN
from dqn.policy import DQNPolicy
from vpg.vpg import VanillaPolicyGradient
from vpg.policy import ActorCritic

ALGOS: Dict[str, Type[Algorithm]] = {
    "dqn": DQN,
    "vpg": VanillaPolicyGradient,
}

POLICIES: Dict[str, Type[Policy]] = {
    "dqn": DQNPolicy,
    "vpg": ActorCritic,
}

HYPERPARAMS_PATH = "hyperparams"


class Hyperparams(TypedDict, total=False):
    device: str
    n_timesteps: Union[int, float]
    env_hyperparams: Dict
    policy_hyperparams: Dict
    algo_hyperparams: Dict


def load_hyperparams(algo: str, env_id: str, root_path: str) -> Hyperparams:
    hyperparams_path = os.path.join(root_path, HYPERPARAMS_PATH, f"{algo}.yml")
    with open(hyperparams_path, "r") as f:
        hyperparams_dict = yaml.safe_load(f)
    return hyperparams_dict[env_id]


def make_env(
    env_id: str,
    render: bool = False,
    n_envs: int = 1,
    frame_stack: int = 1,
    make_kwargs: Optional[Dict[str, Any]] = None,
    no_reward_timeout_steps: Optional[int] = None,
    vec_env_class: str = "dummy",
) -> VecEnv:
    if "BulletEnv" in env_id:
        import pybullet_envs

    make_kwargs = make_kwargs if make_kwargs is not None else {}
    if "BulletEnv" in env_id and render:
        make_kwargs["render"] = True

    spec = gym.spec(env_id)

    def make() -> gym.Env:
        if "AtariEnv" in spec.entry_point:  # type: ignore
            from gym.wrappers.atari_preprocessing import AtariPreprocessing
            from gym.wrappers.frame_stack import FrameStack

            env = gym.make(env_id, **make_kwargs)
            env = AtariPreprocessing(env)
            env = FrameStack(env, frame_stack)
        elif "CarRacing" in env_id:
            from gym.wrappers.resize_observation import ResizeObservation
            from gym.wrappers.gray_scale_observation import GrayScaleObservation
            from gym.wrappers.frame_stack import FrameStack

            env = gym.make(env_id, verbose=0, **make_kwargs)
            env = ResizeObservation(env, (64, 64))
            env = GrayScaleObservation(env, keep_dim=False)
            env = FrameStack(env, frame_stack)
        else:
            env = gym.make(env_id, **make_kwargs)

        if no_reward_timeout_steps:
            from wrappers.no_reward_timeout import NoRewardTimeout

            env = NoRewardTimeout(env, no_reward_timeout_steps)

        return env

    VecEnvClass = {"dummy": DummyVecEnv, "subproc": SubprocVecEnv}[vec_env_class]
    return VecEnvClass([make for i in range(n_envs)])


def make_policy(
    algo: str,
    env: VecEnv,
    device: torch.device,
    load_path: Optional[str] = None,
    **kwargs,
) -> Policy:
    policy = POLICIES[algo](env, device, **kwargs)
    if load_path:
        policy.load(load_path)
    return policy


@dataclass
class Names:
    algo_name: str
    env_name: str
    hyperparams: Hyperparams
    root_dir: str
    run_id: str = datetime.now().isoformat()

    @property
    def model_name(self) -> str:
        model_name = f"{self.algo_name}-{self.env_name}"
        make_kwargs = self.hyperparams.get("env_hyperparams", {}).get("make_kwargs", {})
        if make_kwargs:
            for k, v in make_kwargs.items():
                if type(v) == bool and v:
                    model_name += f"-{k}"
                elif type(v) == int and v:
                    model_name += f"-{k}{v}"
                else:
                    model_name += f"-{v}"
        return model_name

    @property
    def run_name(self) -> str:
        return f"{self.model_name}-{self.run_id}"

    @property
    def saved_models_dir(self) -> str:
        return os.path.join(self.root_dir, "saved_models")

    def model_path(
        self,
        best: bool = False,
        include_run_id: bool = False,
    ) -> str:
        model_file_name = (
            (self.run_name if include_run_id else self.model_name)
            + ("-best" if best else "")
            + ".pt"
        )
        return os.path.join(self.saved_models_dir, model_file_name)

    @property
    def results_path(self) -> str:
        return os.path.join(self.root_dir, "results")

    @property
    def logs_path(self) -> str:
        return os.path.join(self.results_path, f"log.yml")

    @property
    def training_plot_path(self) -> str:
        return os.path.join(self.results_path, f"{self.run_name}-train.png")

    @property
    def eval_plot_path(self) -> str:
        return os.path.join(self.results_path, f"{self.run_name}-eval.png")


def plot_training(history: List[EpisodesStats], plot_path: str) -> None:
    cumulative_steps = []
    for es in history:
        cumulative_steps.append(
            es.length.sum() + (cumulative_steps[-1] if cumulative_steps else 0)
        )

    plt.plot(cumulative_steps, [es.score.mean for es in history])
    plt.fill_between(
        cumulative_steps,
        [es.score.min for es in history],  # type: ignore
        [es.score.max for es in history],  # type: ignore
        facecolor="cyan",
    )
    plt.xlabel("Steps")
    plt.ylabel("Score")
    plt.plot()
    plt.savefig(plot_path)


def plot_eval_callback(callback: EvalCallback, plot_path: str) -> None:
    cumulative_steps = [
        (idx + 1) * callback.step_freq for idx in range(len(callback.stats))
    ]
    plt.clf()
    plt.plot(
        cumulative_steps,
        [s.score.mean for s in callback.stats],
        "b-",
        label="mean",
    )
    plt.plot(
        cumulative_steps,
        [s.score.mean - s.score.std for s in callback.stats],
        "g--",
        label="mean-std",
    )
    plt.fill_between(
        cumulative_steps,
        [s.score.min for s in callback.stats],  # type: ignore
        [s.score.max for s in callback.stats],  # type: ignore
        facecolor="cyan",
        label="range",
    )
    plt.xlabel("Steps")
    plt.ylabel("Score")
    plt.legend()
    plt.plot()
    plt.savefig(plot_path)
