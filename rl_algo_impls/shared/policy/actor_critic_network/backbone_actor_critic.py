import os
from typing import Dict, List, Optional, Sequence, Tuple, Union

import numpy as np
import torch
import torch.nn as nn
from gymnasium.spaces import Box
from gymnasium.spaces import Dict as DictSpace
from gymnasium.spaces import MultiDiscrete, Space

from rl_algo_impls.shared.actor import pi_forward
from rl_algo_impls.shared.actor.gridnet import GridnetDistribution, ValueDependentMask
from rl_algo_impls.shared.actor.gridnet_decoder import Transpose
from rl_algo_impls.shared.module.channelwise_activation import ChannelwiseActivation
from rl_algo_impls.shared.module.normalization import (
    NormalizationMethod,
    normalization1d,
    normalization2d,
)
from rl_algo_impls.shared.module.stack import HStack
from rl_algo_impls.shared.module.utils import layer_init
from rl_algo_impls.shared.policy.actor_critic_network.network import (
    ACNForward,
    ActorCriticNetwork,
)
from rl_algo_impls.shared.policy.policy import ACTIVATION, MODEL_FILENAME

CRITIC_FILENAME = "critic.pth"


class BackboneActorCritic(ActorCriticNetwork):
    def __init__(
        self,
        observation_space: Space,
        action_space: Space,
        action_plane_space: Space,
        backbone: nn.Module,
        backbone_out_channels: int,
        num_additional_critics: int = 0,
        additional_critic_activation_functions: Optional[List[str]] = None,
        critic_channels: int = 64,
        init_layers_orthogonal: bool = True,
        cnn_layers_init_orthogonal: bool = False,
        strides: Sequence[Union[int, Sequence[int]]] = (2, 2),
        output_activation_fn: str = "identity",
        subaction_mask: Optional[Dict[int, Dict[int, int]]] = None,
        critic_shares_backbone: bool = True,
        save_critic_separate: bool = False,
        shared_critic_head: bool = False,
        normalization: Optional[str] = None,
    ):
        if num_additional_critics and not additional_critic_activation_functions:
            additional_critic_activation_functions = [
                "identity"
            ] * num_additional_critics

        super().__init__()
        self.backbone = backbone

        assert isinstance(observation_space, Box)
        assert isinstance(action_plane_space, MultiDiscrete)
        self.range_size = np.max(observation_space.high) - np.min(observation_space.low)
        self.action_vec = action_plane_space.nvec
        if isinstance(action_space, DictSpace):
            action_space_per_position = action_space["per_position"]
            pick_position_space = action_space["pick_position"]
            assert isinstance(pick_position_space, MultiDiscrete)
            self.pick_vec = pick_position_space.nvec
        elif isinstance(action_space, MultiDiscrete):
            action_space_per_position = action_space
            self.pick_vec = None
        else:
            raise ValueError(
                f"action_space {action_space.__class__.__name__} must be MultiDiscrete or gymnasium Dict of MultiDiscrete"
            )
        self.subaction_mask = subaction_mask
        self.critic_shares_backbone = critic_shares_backbone
        self.save_critic_separate = save_critic_separate
        if save_critic_separate:
            assert (
                not critic_shares_backbone
            ), "Cannot save critic separate if sharing backbone"
        self.shared_critic_head = shared_critic_head

        assert isinstance(action_space_per_position, MultiDiscrete)
        self.map_size = len(action_space_per_position.nvec) // len(
            action_plane_space.nvec
        )

        assert (
            normalization is None or normalization.upper() in NormalizationMethod.__members__
        ), f"Invalid normalization method {normalization}"

        self.actor_head = nn.Sequential(
            *[
                layer_init(
                    nn.Conv2d(
                        in_channels=backbone_out_channels,
                        out_channels=self.action_vec.sum()
                        + (len(self.pick_vec) if self.pick_vec else 0),
                        kernel_size=3,
                        padding=1,
                    ),
                    init_layers_orthogonal=init_layers_orthogonal,
                    std=0.01,
                ),
                Transpose((0, 2, 3, 1)),
            ]
        )

        def critic_head(
            output_activation_layer: nn.Module, num_output_channels: int = 1
        ) -> nn.Module:
            def down_conv(
                in_channels: int, out_channels: int, stride: int
            ) -> List[nn.Module]:
                kernel_size = max(3, stride)
                layers = [
                    layer_init(
                        nn.Conv2d(
                            in_channels,
                            out_channels,
                            kernel_size,
                            stride=stride,
                            padding=1 if kernel_size % 2 else 0,
                        ),
                        init_layers_orthogonal=cnn_layers_init_orthogonal,
                    )
                ]
                if normalization:
                    layers.append(normalization2d(normalization, out_channels))
                layers.append(nn.GELU())
                return layers

            flattened_strides = []
            for s in strides:
                if isinstance(s, list):
                    flattened_strides.extend(s)
                else:
                    flattened_strides.append(s)

            critic_in_channels = (
                backbone_out_channels
                if critic_shares_backbone
                else observation_space.shape[0]  # type: ignore
            )
            down_convs = down_conv(
                critic_in_channels, critic_channels, flattened_strides[0]
            )
            for s in flattened_strides[1:]:
                down_convs.extend(down_conv(critic_channels, critic_channels, s))
            _layers = down_convs + [
                nn.AdaptiveAvgPool2d(1),
                nn.Flatten(),
                layer_init(
                    nn.Linear(critic_channels, critic_channels),
                    init_layers_orthogonal=init_layers_orthogonal,
                ),
            ]
            if normalization:
                _layers.append(normalization1d(normalization, critic_channels))
            _layers.append(nn.GELU())
            _layers.append(
                layer_init(
                    nn.Linear(critic_channels, num_output_channels),
                    init_layers_orthogonal=init_layers_orthogonal,
                    std=1.0,
                )
            )
            _layers.append(output_activation_layer)
            return nn.Sequential(*_layers)

        output_activations = [
            ACTIVATION[act_fn_name]()
            for act_fn_name in [output_activation_fn]
            + (additional_critic_activation_functions or [])
        ]
        self._critic_features = len(output_activations)
        if self.shared_critic_head:
            self.critic_heads = critic_head(
                ChannelwiseActivation(output_activations),
                num_output_channels=len(output_activations),
            )
        else:
            self.critic_heads = HStack(
                [critic_head(activation) for activation in output_activations]
            )

    def _preprocess(self, obs: torch.Tensor) -> torch.Tensor:
        if len(obs.shape) == 3:
            obs = obs.unsqueeze(0)
        return obs.float() / self.range_size

    def _distribution_and_value(
        self,
        obs: torch.Tensor,
        action: Optional[torch.Tensor] = None,
        action_masks: Optional[torch.Tensor] = None,
    ) -> ACNForward:
        assert (
            action_masks is not None
        ), f"No mask case unhandled in {self.__class__.__name__}"

        o = self._preprocess(obs)
        x = self.backbone(o)
        logits = self.actor_head(x)
        pi = GridnetDistribution(
            int(np.prod(o.shape[-2:])),
            self.action_vec,
            logits,
            action_masks,
            subaction_mask=ValueDependentMask.from_reference_index_to_index_to_value(
                self.subaction_mask
            )
            if self.subaction_mask
            else None,
        )

        v = self.critic_heads(x if self.critic_shares_backbone else o)
        if v.shape[-1] == 1:
            v = v.squeeze(-1)

        return ACNForward(pi_forward(pi, action), v)

    def value(self, obs: torch.Tensor) -> torch.Tensor:
        x = self._preprocess(obs)
        if self.critic_shares_backbone:
            x = self.backbone(x)
        v = self.critic_heads(x)
        if v.shape[-1] == 1:
            v = v.squeeze(-1)
        return v

    def reset_noise(self, batch_size: Optional[int] = None) -> None:
        pass

    @property
    def action_shape(self) -> Union[Tuple[int, ...], Dict[str, Tuple[int, ...]]]:
        per_position_action_shape = (self.map_size, len(self.action_vec))
        if self.pick_vec:
            return {
                "per_position": per_position_action_shape,
                "pick_position": (len(self.pick_vec),),
            }
        return per_position_action_shape

    @property
    def value_shape(self) -> Tuple[int, ...]:
        if self._critic_features > 1:
            return (self._critic_features,)
        else:
            return ()

    def freeze(
        self,
        freeze_policy_head: bool,
        freeze_value_head: bool,
        freeze_backbone: bool = True,
    ) -> None:
        for p in self.actor_head.parameters():
            p.requires_grad = not freeze_policy_head
        for p in self.critic_heads.parameters():
            p.requires_grad = not freeze_value_head
        for p in self.backbone.parameters():
            p.requires_grad = not freeze_backbone

    def save(self, path: str) -> None:
        assert (
            not self.critic_shares_backbone
        ), "Should not be called if sharing backbone"
        torch.save(
            {
                "actor_head": self.actor_head.state_dict(),
                "backbone": self.backbone.state_dict(),
            },
            os.path.join(path, MODEL_FILENAME),
        )
        torch.save(self.critic_heads.state_dict(), os.path.join(path, CRITIC_FILENAME))

    def load(self, path: str, device: Optional[torch.device]) -> None:
        assert (
            not self.critic_shares_backbone
        ), "Should not be called if sharing backbone"
        policy_weights = torch.load(
            os.path.join(path, MODEL_FILENAME), map_location=device
        )
        self.backbone.load_state_dict(policy_weights["backbone"])
        self.actor_head.load_state_dict(policy_weights["actor_head"])
        self.critic_heads.load_state_dict(
            torch.load(os.path.join(path, CRITIC_FILENAME), map_location=device)
        )
