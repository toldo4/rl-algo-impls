import logging
from typing import Any, List, Optional, Tuple

import gym
import gym.spaces
import gym.vector
import numpy as np
from gym.vector.utils.spaces import batch_space

from rl_algo_impls.microrts.vec_env.microrts_interface import (
    ByteArray,
    MicroRTSInterface,
    MicroRTSInterfaceListener,
)
from rl_algo_impls.microrts.vec_env.planes import (
    MultiplierPlane,
    ObservationTransform,
    OffsetPlane,
    OffsetThresholdPlane,
    OneHotPlane,
    Planes,
    ThresholdPlane,
)
from rl_algo_impls.wrappers.vectorable_wrapper import VecEnvStepReturn

MAX_HP = 10
MAX_RESOURCES = 40
ACTION_TYPE_TO_ACTION_INDEXES = {
    0: {},  # NOOP
    1: {1},  # move
    2: {2},  # harvest
    3: {3},  # return
    4: {4, 5},  # produce
    5: {6},  # attack
}

PAPER_PLANES = [
    Planes("hp", [MultiplierPlane(1 / MAX_HP)]),
    Planes("resources", [MultiplierPlane(1 / MAX_RESOURCES), ThresholdPlane(1)]),
    Planes("owner", [OneHotPlane(3)]),
    Planes("unit_type", [OneHotPlane(8, set_out_of_range_to_0=True)]),
    Planes("action", [OneHotPlane(6)]),
    Planes("terrain", [OneHotPlane(2)]),
]


class MicroRTSSpaceTransform(gym.vector.VectorEnv, MicroRTSInterfaceListener):
    def __init__(
        self,
        interface: MicroRTSInterface,
        valid_sizes: Optional[List[int]] = None,
        paper_planes_sizes: Optional[List[int]] = None,
    ) -> None:
        self.interface = interface
        self.valid_sizes = list(sorted(valid_sizes)) if valid_sizes else None
        self.paper_planes_sizes = set(paper_planes_sizes if paper_planes_sizes else [])

        # computed properties
        # [hp, resources, resources_non_zero, num_planes_player(5),
        # num_planes_unit_type(z), num_planes_unit_action(6)]
        self.metadata = self.interface.metadata

        self.planes = ObservationTransform(
            [
                Planes("hp", [MultiplierPlane(1 / MAX_HP)]),
                Planes(
                    "resources", [MultiplierPlane(1 / MAX_RESOURCES), ThresholdPlane(1)]
                ),
                Planes("owner", [OneHotPlane(3)]),
                Planes(
                    "unit_type", [OneHotPlane(len(self.interface.utt["unitTypes"]) + 2)]
                ),
                Planes("action", [OneHotPlane(6)]),
                Planes("move_dir", [OneHotPlane(5)]),
                Planes("harvest_dir", [OneHotPlane(5)]),
                Planes("return_dir", [OneHotPlane(5)]),
                Planes("produce_dir", [OneHotPlane(5)]),
                Planes("produce_type", [OneHotPlane(8)]),
                Planes("attack_dir", [OneHotPlane(6)]),
                Planes(
                    "eta",
                    [
                        OffsetPlane(multiplier=1 / 255, offset=128),
                        OffsetThresholdPlane(offset=128, min_threshold=5),
                        OffsetThresholdPlane(offset=128, min_threshold=10),
                    ],
                ),
                Planes("terrain", [OneHotPlane(2)]),
            ]
        )
        if self.interface.partial_obs:
            self.planes.append(Planes("observable", [OneHotPlane(2)]))

        if self.paper_planes_sizes:
            self.paper_planes = ObservationTransform(PAPER_PLANES, self.planes)

        self.resources_planes = ObservationTransform(
            [
                Planes(
                    "player_{p}_resources",
                    [
                        MultiplierPlane(1 / 32, clip_expected=True),
                        ThresholdPlane(1),  # Worker Cost
                        ThresholdPlane(2),  # Light or Ranged Cost
                        ThresholdPlane(3),  # Heavy Cost
                        ThresholdPlane(5),  # Barracks Cost
                        ThresholdPlane(10),  # Base Cost
                        ThresholdPlane(32),
                    ],
                )
                for p in range(2)
            ]
        )

        self.height, self.width = 0, 0
        spaces = self._update_spaces(is_init=True)
        assert spaces is not None
        observation_space, action_space = spaces

        super().__init__(self.interface.num_envs, observation_space, action_space)

        self.interface.add_listener(self)

    def _update_spaces(
        self, is_init: bool = False
    ) -> Optional[Tuple[gym.spaces.Box, gym.spaces.MultiDiscrete]]:
        # Set height and width to next factor of 4 if not factor of 4 already
        next_factor_of_4 = lambda n: n + 4 - n % 4 if n % 4 else n
        height = next_factor_of_4(np.max(self.interface.heights))
        width = next_factor_of_4(np.max(self.interface.widths))
        assert height % 4 == 0, f"{height} must be multiple of 4"
        assert width % 4 == 0, f"{width} must be multiple of 4"
        sz = max(height, width)
        if self.valid_sizes is not None:
            for valid_sz in self.valid_sizes:
                if sz <= valid_sz:
                    sz = valid_sz
                    break
            else:
                logging.warning(
                    f"Map size {sz} larger than all valid sizes {self.valid_sizes}"
                )
        if not is_init and sz == self.height and sz == self.width:
            return None

        self.height = sz
        self.width = sz

        if sz in self.paper_planes_sizes:
            num_features = self.paper_planes.n_dim
        else:
            num_features = self.planes.n_dim + self.resources_planes.n_dim

        observation_space = gym.spaces.Box(
            low=0.0,
            high=1.0,
            shape=(
                self.height,
                self.width,
                num_features,
            ),
            dtype=np.float32,
        )

        action_space_dims = [6, 4, 4, 4, 4, len(self.interface.utt["unitTypes"]), 7 * 7]
        action_space = gym.spaces.MultiDiscrete(
            np.array([action_space_dims] * self.height * self.width).flatten().tolist()
        )

        self.action_plane_space = gym.spaces.MultiDiscrete(action_space_dims)

        self.source_unit_idxs = np.tile(
            np.arange(self.height * self.width), (self.num_envs, 1)
        )
        self.source_unit_idxs = self.source_unit_idxs.reshape(
            (self.source_unit_idxs.shape + (1,))
        )

        if is_init:
            return observation_space, action_space
        else:
            self.single_observation_space = observation_space
            self.observation_space = batch_space(
                observation_space, n=self.interface.num_envs
            )
            self.single_action_space = action_space
            self.action_space = gym.spaces.Tuple(
                (action_space,) * self.interface.num_envs
            )

    def map_size_change(
        self, old_heights: List[int], old_widths: List[int], indexes_changed: int
    ) -> None:
        self._update_spaces(is_init=False)

    @property
    def obs_dim(self) -> int:
        return len(self.planes)

    def __getattr__(self, name: str) -> Any:
        return getattr(self.interface, name)

    def step(self, actions) -> VecEnvStepReturn:
        microrts_action = self._to_microrts_action(actions)
        microrts_obs, microrts_mask, r, d, i = self.interface.step(microrts_action)
        self._update_action_mask(microrts_mask)

        obs = self._from_microrts_obs(microrts_obs)
        return obs, r, d, i

    def reset(self) -> np.ndarray:
        microrts_obs, microrts_mask = self.interface.reset()
        self._update_action_mask(microrts_mask)
        return self._from_microrts_obs(microrts_obs)

    def render(self, mode="human"):
        return self.interface.render(mode)

    def close_extras(self, **kwargs):
        self.interface.remove_listener(self)
        self.interface.close(**kwargs)

    def _translate_actions(
        self, actions: List[List[int]], env_idx: int
    ) -> List[List[int]]:
        map_h = self.interface.heights[env_idx]
        map_w = self.interface.widths[env_idx]
        if map_h == self.height and map_w == self.width:
            return actions
        pad_h = (self.height - map_h) // 2
        pad_w = (self.width - map_w) // 2
        for a in actions:
            y = a[0] // self.width
            x = a[0] % self.width
            a[0] = (y - pad_h) * map_w + x - pad_w
        return actions

    def _verify_actions(self, actions: List[List[int]], env_idx: int):
        matrix_mask = self.interface.debug_matrix_mask(env_idx)
        if matrix_mask is None:
            return
        env_w = self.interface.widths[env_idx]

        if len(actions) != np.sum(matrix_mask[:, :, 0]):
            logging.error(
                f"# Actions mismatch: Env {env_idx}, # Actions {len(actions)} (Should be {np.sum(matrix_mask[:, :, 0])})"
            )
        for a in actions:
            m = matrix_mask[a[0] // env_w, a[0] % env_w]
            if m[0] == 0:
                logging.error(f"No action allowed: Env {env_idx}, loc {a[0]}")
            offset = 1
            for idx, sz in enumerate(self.action_plane_space.nvec):
                valid = m[offset : offset + sz]
                offset += sz
                if np.all(valid == 0):
                    continue
                if not valid[a[idx + 1]]:
                    if idx == 0 or idx in ACTION_TYPE_TO_ACTION_INDEXES[a[1]]:
                        logging.error(
                            f"Invalid action in env {env_idx}, loc {a[0]}, action {a[1:]}, idx {idx+1}, valid {valid}"
                        )

    def _to_microrts_action(self, actions: np.ndarray) -> List[List[List[int]]]:
        actions = actions.reshape((self.num_envs, self.height * self.width, -1))
        actions = np.concatenate((self.source_unit_idxs, actions), 2)
        actions = actions[np.where(self.source_unit_mask == 1)]
        action_counts_per_env = self.source_unit_mask.sum(1)

        actions_per_env = []
        action_idx = 0
        for idx, action_count in enumerate(action_counts_per_env):
            actions_in_env = []
            for _ in range(action_count):
                actions_in_env.append(actions[action_idx].tolist())
                action_idx += 1
            actions_per_env.append(self._translate_actions(actions_in_env, idx))
            self._verify_actions(actions_per_env[-1], idx)
        return actions_per_env

    def _from_microrts_obs(self, microrts_obs: List[ByteArray]) -> np.ndarray:
        return np.array(
            [self._encode_obs(o, idx) for idx, o in enumerate(microrts_obs)]
        )

    def _get_matrix_obs(self, obs_bytes: ByteArray, env_idx: int) -> np.ndarray:
        env_h = self.interface.heights[env_idx]
        env_w = self.interface.widths[env_idx]
        obs_array = obs_bytes.reshape((-1, self.obs_dim + 1))

        o = np.zeros((env_h, env_w, self.obs_dim), dtype=obs_array.dtype)
        o[:, :, 11] = -128  # ETA offset by -128
        o[:, :, -1] = self.interface.terrain(env_idx)
        o[obs_array[:, 0], obs_array[:, 1], :-1] = obs_array[:, 2:]

        if env_h == self.height and env_w == self.width:
            return o

        obs = np.zeros((self.height, self.width, self.obs_dim), dtype=o.dtype)
        obs[:, :, -1] = 1
        pad_h = (self.height - env_h) // 2
        pad_w = (self.width - env_w) // 2
        obs[pad_h : pad_h + env_h, pad_w : pad_w + env_w, :] = o
        return obs

    def _verify_matrix_obs(self, obs: np.ndarray, env_idx: int) -> None:
        matrix_obs = self.interface.debug_matrix_obs(env_idx)
        if matrix_obs is None:
            return

        env_h = self.interface.heights[env_idx]
        env_w = self.interface.widths[env_idx]
        pad_h = (self.height - env_h) // 2
        pad_w = (self.width - env_w) // 2
        diffs = np.transpose(
            np.array(
                np.where(
                    matrix_obs != obs[pad_h : pad_h + env_h, pad_w : pad_w + env_w]
                )
            )
        )
        if len(diffs):
            logging.error(f"Observation differences in env {env_idx}: {diffs}")

    def _encode_obs(self, obs_bytes: ByteArray, env_idx: int) -> np.ndarray:
        obs = self._get_matrix_obs(obs_bytes, env_idx)
        self._verify_matrix_obs(obs, env_idx)
        obs = obs.reshape(-1, obs.shape[-1])

        use_paper_planes = self.height in self.paper_planes_sizes

        obs_transform = self.planes if not use_paper_planes else self.paper_planes
        obs_planes = np.zeros(
            (self.height * self.width, obs_transform.n_dim), dtype=np.float32
        )

        destination_col = 0
        for source_col, p in enumerate(obs_transform):
            destination_col = p.transform(obs, source_col, obs_planes, destination_col)
        assert destination_col == obs_planes.shape[-1]

        obs_out = obs_planes.reshape(self.height, self.width, -1)

        if use_paper_planes:
            return obs_out

        resources = np.expand_dims(self.interface.resources(env_idx), 0)
        destination_col = 0
        resource_planes = np.zeros(
            (1, self.resources_planes.n_dim), dtype=obs_planes.dtype
        )
        for source_col, rp in enumerate(self.resources_planes):
            destination_col = rp.transform(
                resources, source_col, resource_planes, destination_col
            )
        assert destination_col == resource_planes.shape[-1]

        return np.concatenate(
            [
                obs_out,
                np.ones(
                    (self.height, self.width, resource_planes.shape[-1]),
                    dtype=resource_planes.dtype,
                )
                * resource_planes,
            ],
            axis=-1,
        )

    def get_action_mask(self) -> np.ndarray:
        return self._action_mask

    def _update_action_mask(self, microrts_mask: List[ByteArray]) -> None:
        masks = []
        for idx, m_bytes in enumerate(microrts_mask):
            action_plane_dim = np.sum(self.action_plane_space.nvec)
            m_array = m_bytes.reshape(-1, action_plane_dim + 2)
            env_h = self.interface.heights[idx]
            env_w = self.interface.widths[idx]
            m = np.zeros((env_h, env_w, action_plane_dim + 1), dtype=np.bool_)
            m[m_array[:, 0], m_array[:, 1], 0] = 1
            m[m_array[:, 0], m_array[:, 1], 1:] = m_array[:, 2:]
            if env_h == self.height and env_w == self.width:
                masks.append(m)
                continue
            new_m = np.zeros((self.height, self.width, m.shape[-1]), dtype=m.dtype)
            pad_h = (self.height - env_h) // 2
            pad_w = (self.width - env_w) // 2
            new_m[pad_h : pad_h + env_h, pad_w : pad_w + env_w] = m
            masks.append(new_m)
        action_mask = np.array(masks)
        self._verify_action_mask(action_mask)
        self.source_unit_mask = action_mask[:, :, :, 0].reshape(self.num_envs, -1)
        self._action_mask = action_mask[:, :, :, 1:].reshape(
            self.num_envs, self.height * self.width, -1
        )

    def _verify_action_mask(self, masks: np.ndarray) -> None:
        for env_idx, mask in enumerate(masks):
            matrix_mask = self.interface.debug_matrix_mask(env_idx)
            if matrix_mask is None:
                continue

            env_h = self.interface.heights[env_idx]
            env_w = self.interface.widths[env_idx]
            pad_h = (self.height - env_h) // 2
            pad_w = (self.width - env_w) // 2
            diffs = np.transpose(
                np.array(
                    np.where(
                        matrix_mask
                        != mask[pad_h : pad_h + env_h, pad_w : pad_w + env_w]
                    )
                )
            )
            if len(diffs):
                logging.error(f"Mask differences in env {env_idx}: {diffs}")