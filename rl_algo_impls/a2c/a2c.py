import logging
from dataclasses import astuple
from time import perf_counter
from typing import List, Optional, TypeVar

import numpy as np
import torch
import torch.nn as nn

from rl_algo_impls.a2c.train_stats import TrainStats, TrainStepStats
from rl_algo_impls.rollout.rollout import RolloutGenerator
from rl_algo_impls.shared.algorithm import Algorithm
from rl_algo_impls.shared.autocast import maybe_autocast
from rl_algo_impls.shared.callbacks import Callback
from rl_algo_impls.shared.callbacks.summary_wrapper import SummaryWrapper
from rl_algo_impls.shared.policy.actor_critic import ActorCritic
from rl_algo_impls.shared.schedule import update_learning_rate
from rl_algo_impls.shared.stats import log_scalars
from rl_algo_impls.shared.tensor_utils import NumOrList, num_or_array

A2CSelf = TypeVar("A2CSelf", bound="A2C")


class A2C(Algorithm):
    def __init__(
        self,
        policy: ActorCritic,
        device: torch.device,
        tb_writer: SummaryWrapper,
        learning_rate: float = 7e-4,
        gamma: NumOrList = 0.99,
        gae_lambda: NumOrList = 1.0,
        ent_coef: float = 0.0,
        vf_coef: NumOrList = 0.5,
        max_grad_norm: float = 0.5,
        rms_prop_eps: float = 1e-5,
        use_rms_prop: bool = True,
        normalize_advantage: bool = False,
        multi_reward_weights: Optional[List[int]] = None,
        scale_loss_by_num_actions: bool = False,
        gradient_accumulation: bool = False,
        autocast_loss: bool = False,
        num_minibatches: Optional[int] = None,
    ) -> None:
        if use_rms_prop:
            optimizer = torch.optim.RMSprop(
                policy.parameters(), lr=learning_rate, eps=rms_prop_eps
            )
        else:
            optimizer = torch.optim.Adam(policy.parameters(), lr=learning_rate)
        super().__init__(policy, device, tb_writer, learning_rate, optimizer)
        self.policy = policy

        self.gamma = num_or_array(gamma)
        self.gae_lambda = num_or_array(gae_lambda)

        self.vf_coef = num_or_array(vf_coef)
        self.ent_coef = ent_coef
        self.max_grad_norm = max_grad_norm

        self.normalize_advantage = normalize_advantage

        self.multi_reward_weights = (
            np.array(multi_reward_weights) if multi_reward_weights else None
        )
        self.scale_loss_by_num_actions = scale_loss_by_num_actions

        self.gradient_accumulation = gradient_accumulation
        self.autocast_loss = autocast_loss
        self.num_minibatches = num_minibatches or 1
        assert self.num_minibatches == 1 or self.gradient_accumulation, (
            "A2C only supports single step batches. Therefore, non-1 minibatches "
            "must be gradient accumulated"
        )

    def learn(
        self: A2CSelf,
        train_timesteps: int,
        rollout_generator: RolloutGenerator,
        callbacks: Optional[List[Callback]] = None,
        total_timesteps: Optional[int] = None,
        start_timesteps: int = 0,
    ) -> A2CSelf:
        if total_timesteps is None:
            total_timesteps = train_timesteps
        assert start_timesteps + train_timesteps <= total_timesteps

        timesteps_elapsed = start_timesteps
        while timesteps_elapsed < start_timesteps + train_timesteps:
            start_time = perf_counter()

            update_learning_rate(self.optimizer, self.learning_rate)
            chart_scalars = {
                "ent_coef": self.ent_coef,
                "learning_rate": self.learning_rate,
                "gamma": self.gamma,
                "gae_lambda": self.gae_lambda,
                "vf_coef": self.vf_coef,
            }

            if self.multi_reward_weights is not None:
                chart_scalars["reward_weights"] = self.multi_reward_weights
            log_scalars(self.tb_writer, "charts", chart_scalars, timesteps_elapsed)

            r = rollout_generator.rollout(gamma=self.gamma, gae_lambda=self.gae_lambda)
            timesteps_elapsed += r.total_steps

            vf_coef = torch.Tensor(np.array(self.vf_coef)).to(self.device)
            step_stats = []

            multi_reward_weights = (
                torch.Tensor(self.multi_reward_weights).to(self.device)
                if self.multi_reward_weights is not None
                else None
            )
            for mb in r.minibatches(
                r.total_steps // self.num_minibatches,
                shuffle=not self.gradient_accumulation,
            ):
                (
                    mb_obs,
                    _,
                    mb_actions,
                    mb_action_masks,
                    mb_num_actions,
                    _,
                    mb_advantages,
                    mb_returns,
                    _,  # mb_additional,
                ) = astuple(mb)

                if self.normalize_advantage:
                    mb_advantages = (mb_advantages - mb_advantages.mean(0)) / (
                        mb_advantages.std(0) + 1e-8
                    )
                if multi_reward_weights is not None:
                    mb_advantages = mb_advantages @ multi_reward_weights

                with maybe_autocast(self.autocast_loss, self.device):
                    logp_a, entropy, v = self.policy(
                        mb_obs, mb_actions, action_masks=mb_action_masks
                    )

                    if self.scale_loss_by_num_actions:
                        logp_a = torch.where(
                            mb_num_actions > 0, logp_a / mb_num_actions, 0
                        )
                    pi_loss = -(mb_advantages * logp_a)
                    pi_loss = pi_loss.mean()

                    value_loss = ((v - mb_returns) ** 2).mean(0)
                    entropy_loss = -entropy.mean()

                    loss = (
                        pi_loss
                        + (vf_coef * value_loss).sum()
                        + self.ent_coef * entropy_loss
                    )

                    if self.gradient_accumulation:
                        loss /= self.num_minibatches
                loss.backward()
                if not self.gradient_accumulation:
                    self.optimizer_step()
                step_stats.append(
                    TrainStepStats(
                        loss.item(),
                        pi_loss.item(),
                        value_loss.detach().cpu().numpy(),
                        entropy_loss.item(),
                    )
                )
            if self.gradient_accumulation:
                self.optimizer_step()

            var_y = np.var(r.y_true).item()
            explained_var = (
                np.nan if var_y == 0 else 1 - np.var(r.y_true - r.y_pred).item() / var_y
            )

            end_time = perf_counter()
            rollout_steps = r.total_steps
            self.tb_writer.add_scalar(
                "train/steps_per_second",
                rollout_steps / (end_time - start_time),
            )

            TrainStats(step_stats, explained_var).write_to_tensorboard(self.tb_writer)

            self.tb_writer.on_steps(rollout_steps)
            if callbacks:
                if not all(
                    c.on_step(timesteps_elapsed=rollout_steps) for c in callbacks
                ):
                    logging.info(
                        f"Callback terminated training at {timesteps_elapsed} timesteps"
                    )
                    break

        return self

    def optimizer_step(self) -> None:
        nn.utils.clip_grad_norm_(self.policy.parameters(), self.max_grad_norm)  # type: ignore
        self.optimizer.step()
        self.optimizer.zero_grad(set_to_none=True)
