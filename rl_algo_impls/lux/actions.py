import logging
from collections import defaultdict
from dataclasses import astuple
from functools import total_ordering
from typing import (
    Any,
    DefaultDict,
    Dict,
    List,
    NamedTuple,
    Optional,
    Tuple,
    TypeVar,
    Union,
)

import numpy as np
from luxai_s2.actions import move_deltas

from rl_algo_impls.lux.agent_config import LuxAgentConfig
from rl_algo_impls.lux.shared import (
    LuxEnvConfig,
    LuxFactory,
    LuxGameState,
    LuxUnit,
    idx_to_pos,
    pos_to_idx,
    pos_to_numpy,
)
from rl_algo_impls.lux.stats import ActionStats

FACTORY_ACTION_SIZES = (
    4,  # do nothing, build light robot, build heavy robot, water lichen
)
FACTORY_ACTION_ENCODED_SIZE = sum(FACTORY_ACTION_SIZES)

UNIT_ACTION_SIZES = (
    6,  # action type
    5,  # move direction
    5,  # transfer direction
    5,  # transfer resource
    5,  # pickup resource
)
UNIT_ACTION_ENCODED_SIZE = sum(UNIT_ACTION_SIZES)
RECHARGE_UNIT_ACTION = UNIT_ACTION_SIZES[0] - 1

SIMPLE_UNIT_ACTION_SIZES = (
    6,  # action type
    4,  # move direction
    4,  # transfer direction
    5,  # transfer resource
    5,  # pickup resource
)
SIMPLE_UNIT_ACTION_ENCODED_SIZE = sum(SIMPLE_UNIT_ACTION_SIZES)


ACTION_SIZES = FACTORY_ACTION_SIZES + UNIT_ACTION_SIZES
SIMPLE_ACTION_SIZES = FACTORY_ACTION_SIZES + SIMPLE_UNIT_ACTION_SIZES


def to_lux_actions(
    player: str,
    state: LuxGameState,
    actions: np.ndarray,
    action_mask: np.ndarray,
    enqueued_actions: Dict[str, Optional[np.ndarray]],
    action_stats: ActionStats,
    agent_cfg: LuxAgentConfig,
) -> Dict[str, Any]:
    cfg = state.env_cfg

    if np.any(action_mask["pick_position"][0]):
        factory_pos_idx = actions["pick_position"][0]
        factory_pos = idx_to_pos(factory_pos_idx, cfg.map_size)

        water_left = state.teams[player].init_water
        metal_left = state.teams[player].init_metal
        factories_to_place = state.teams[player].factories_to_place
        heavy_cost_metal = cfg.ROBOTS["HEAVY"].METAL_COST
        metal = (
            min(metal_left - factories_to_place * heavy_cost_metal, heavy_cost_metal)
            + heavy_cost_metal
        )
        water = (
            cfg.INIT_WATER_METAL_PER_FACTORY if agent_cfg.init_water_constant else metal
        )
        assert factories_to_place > 1 or (
            metal == metal_left and water == water_left
        ), f"Last factory should use last of metal ({metal} -> {metal_left}) and water ({water} -> {water_left})"

        return {
            "metal": metal,
            "water": water,
            "spawn": factory_pos,
        }

    actions = actions["per_position"]
    action_mask = action_mask["per_position"]
    lux_actions = {}

    positions_occupied: Dict[int, str] = {}

    def cancel_action(unit: LuxUnit):
        enqueued_action = enqueued_actions.get(unit.unit_id)
        if enqueued_action is not None:
            lux_actions[unit.unit_id] = []
        elif unit.unit_id in lux_actions:
            del lux_actions[unit.unit_id]

    def resource_amount(unit: Union[LuxUnit, LuxFactory], idx: int) -> int:
        if idx == 4:
            return unit.power
        return astuple(unit.cargo)[idx]

    unit_actions = []
    for u in state.units[player].values():
        if no_valid_unit_actions(u, action_mask, cfg.map_size):
            if cfg.verbose > 2:
                logging.info(f"{state.real_env_steps}: No valid action for unit {u}")
            action_stats.no_valid_action += 1
            positions_occupied[pos_to_idx(u.pos, cfg.map_size)] = u.unit_id
            continue
        unit_actions.append(UnitAction(u, actions[pos_to_idx(u.pos, cfg.map_size), 1:]))
    unit_actions = sorted(unit_actions)

    pickups_by_factory_resource: DefaultDict[
        Tuple[str, int], List[Tuple[int, str]]
    ] = defaultdict(list)

    for u, a in unit_actions:
        action_stats.action_type[a[0]] += 1
        if a[0] == 0:
            # Will be handled next loop
            continue
        positions_occupied[pos_to_idx(u.pos, cfg.map_size)] = u.unit_id

        if a[0] == 1:  # transfer
            direction = a[2] + (1 if agent_cfg.use_simplified_spaces else 0)
            resource = a[3]
            amount = resource_amount(u, resource)
            if resource == 4:
                # Don't transfer last 10% of unit power
                amount -= u.unit_cfg.BATTERY_CAPACITY // 10
            if amount <= 0:
                cancel_action(u)
                action_stats.transfer_cancelled_no_resource += 1
                continue
            num_executions = 1  # TODO: Not efficient (especially for transfer chains)
        elif a[0] == 2:  # pickup
            direction = 0
            resource = a[4]
            _capacity = u.cargo_space if resource < 4 else u.battery_capacity
            _factory = factory_at_pos(state, pos_to_numpy(u.pos))
            assert _factory is not None
            _factory_amount = resource_amount(_factory, resource)
            amount = max(
                min(
                    _capacity - resource_amount(u, resource),
                    _factory_amount - int(min_factory_resources(cfg)[resource]),
                ),
                0,
            )
            if amount <= 0:
                cancel_action(u)
                action_stats.pickup_cancelled_insufficient_resource += 1
                continue
            num_executions = 1
            pickups_by_factory_resource[(_factory.unit_id, resource)].append(
                (amount, u.unit_id)
            )
        elif a[0] == 3:  # dig
            direction = 0
            resource = 0
            amount = 0
            num_executions = cfg.max_episode_length
        elif a[0] == 4:  # self-destruct
            direction = 0
            resource = 0
            amount = 0
            num_executions = 1
        elif a[0] == 5:  # recharge
            direction = 0
            resource = 0
            amount = u.battery_capacity
            num_executions = cfg.max_episode_length
        else:
            raise ValueError(f"Unrecognized action {a[0]}")

        assert num_executions > 0, (
            "num_executions must be positive: "
            f"{np.array([a[0], direction, resource, amount, 0, num_executions])}"
        )
        if actions_equal(a, enqueued_actions.get(u.unit_id)):
            action_stats.repeat_action += 1
            continue

        assert u.power >= u.unit_cfg.ACTION_QUEUE_POWER_COST
        if a[0] == 5:
            lux_actions[u.unit_id] = []
        else:
            lux_actions[u.unit_id] = [
                np.array([a[0], direction, resource, amount, 0, num_executions])
            ]

    for (f_id, resource), pickups in pickups_by_factory_resource.items():
        if len(pickups) == 1:
            continue
        factory = state.factories[player][f_id]
        pickupable_factory_amount = resource_amount(factory, resource) - int(
            min_factory_resources(cfg)[resource]
        )
        if pickupable_factory_amount >= sum(amount for (amount, _) in pickups):
            continue
        for _, u_id in pickups:
            cancel_action(state.units[player][u_id])
            action_stats.pickup_cancelled_simultaneous_pickups += 1

    for f in state.factories[player].values():
        if no_valid_factory_actions(f, action_mask, cfg.map_size):
            continue
        a = actions[pos_to_idx(f.pos, cfg.map_size), 0]
        if a in {1, 2}:
            if pos_to_idx(f.pos, cfg.map_size) in positions_occupied:
                action_stats.build_cancelled += 1
                actions[pos_to_idx(f.pos, cfg.map_size), 0] = 0  # DO_NOTHING
            else:
                positions_occupied[pos_to_idx(f.pos, cfg.map_size)] = "BUILD"

    def cancel_move(unit: LuxUnit):
        cancel_action(unit)
        action_stats.move_cancelled += 1
        u_pos = pos_to_idx(unit.pos, cfg.map_size)
        assert (
            u_pos not in positions_occupied or positions_occupied[u_pos] == "BUILD"
        ), f"{unit} trying to cancel move on occupied position"
        positions_occupied[u_pos] = unit.unit_id

    moving_actions: List[Tuple[str, np.ndarray]] = [
        (u.unit_id, a) for u, a in unit_actions if a[0] == 0
    ]
    needs_to_compute_move = True
    while needs_to_compute_move and moving_actions:
        needs_to_compute_move = False

        moves_occupied: DefaultDict[int, List[Tuple[str, np.ndarray]]] = defaultdict(
            list
        )
        for u_id, a in moving_actions:
            u = state.units[player][u_id]
            direction = a[1] + (1 if agent_cfg.use_simplified_spaces else 0)
            move_delta = move_deltas[direction]
            target_pos_idx = pos_to_idx(pos_to_numpy(u.pos) + move_delta, cfg.map_size)
            if target_pos_idx in positions_occupied:
                cancel_move(u)
                needs_to_compute_move = True
                continue
            moves_occupied[target_pos_idx].append((u_id, a))

        moving_actions = []
        for moves_to_dest in moves_occupied.values():
            if len(moves_to_dest) == 1:
                moving_actions.extend(moves_to_dest)
                continue
            needs_to_compute_move = True
            for cancel_u_id, _ in moves_to_dest:
                cancel_u = state.units[player][cancel_u_id]
                cancel_move(cancel_u)

    for u_id, a in moving_actions:
        u = state.units[player][u_id]
        direction = a[1] + (1 if agent_cfg.use_simplified_spaces else 0)
        move_delta = move_deltas[direction]
        target_pos_idx = pos_to_idx(pos_to_numpy(u.pos) + move_delta, cfg.map_size)

        assert target_pos_idx not in positions_occupied
        positions_occupied[target_pos_idx] = u.unit_id

        resource = 0
        amount = 0
        num_executions = cfg.max_episode_length

        assert num_executions > 0, (
            "num_executions must be positive: "
            f"{np.array([a[0], direction, resource, amount, 0, num_executions])}"
        )
        if actions_equal(a, enqueued_actions.get(u.unit_id)):
            action_stats.repeat_action += 1
            continue

        assert u.power >= u.unit_cfg.ACTION_QUEUE_POWER_COST
        lux_actions[u.unit_id] = [
            np.array([a[0], direction, resource, amount, 0, num_executions])
        ]

    actions_by_u_id = {u.unit_id: a for u, a in unit_actions}
    # Check transfers have valid targets and adjust amounts by capacity
    for u, a in unit_actions:
        if a[0] != 1:
            continue
        target_pos = (
            pos_to_numpy(u.pos)
            + move_deltas[a[2] + (1 if agent_cfg.use_simplified_spaces else 0)]
        )
        if state.board.factory_occupancy_map[target_pos[0], target_pos[1]] != -1:
            continue
        target_unit_id = positions_occupied.get(pos_to_idx(target_pos, cfg.map_size))
        if target_unit_id is None:
            cancel_action(u)
            action_stats.transfer_cancelled_no_target += 1
            continue
        target_unit = state.units[player][target_unit_id]
        resource = a[3]
        target_capacity = (
            target_unit.cargo_space if resource < 4 else target_unit.battery_capacity
        )
        if not (
            target_unit_id in actions_by_u_id
            and actions_by_u_id[target_unit_id][0] == 1
            and actions_by_u_id[target_unit_id][3] == resource
        ):
            target_capacity -= resource_amount(target_unit, resource)
        amount = min(lux_actions[u.unit_id][0][3], target_capacity)
        if amount <= 0:
            cancel_action(u)
            action_stats.transfer_cancelled_target_full += 1
            continue
        lux_actions[u.unit_id][0][3] = amount

    for f in state.factories[player].values():
        if no_valid_factory_actions(f, action_mask, cfg.map_size):
            continue
        f_pos_idx = pos_to_idx(f.pos, cfg.map_size)
        a = actions[f_pos_idx, 0]
        if a > 0:
            if a in {1, 2} and positions_occupied[f_pos_idx] != "BUILD":
                # Likely a unit that was going to move out had to cancel its action.
                # Cancel the build.
                action_stats.build_cancelled += 1
                continue
            lux_actions[f.unit_id] = a - 1

    return lux_actions


UnitActionSelf = TypeVar("UnitActionSelf", bound="UnitAction")


def is_move_action(action: np.ndarray) -> bool:
    return action[0] == 0 and action[1] > 0


@total_ordering
class UnitAction(NamedTuple):
    unit: LuxUnit
    action: np.ndarray

    def __lt__(self: UnitActionSelf, other: UnitActionSelf) -> bool:
        # Units that aren't moving have priority
        is_move = is_move_action(self.action)
        is_other_move = is_move_action(other.action)
        if is_move != is_other_move:
            return not is_move

        # Next heavy units have priority
        is_unit_heavy = self.unit.is_heavy()
        is_other_unit_heavy = other.unit.is_heavy()
        if is_unit_heavy != is_other_unit_heavy:
            return is_unit_heavy

        return False

    def __eq__(self: UnitActionSelf, other: UnitActionSelf) -> bool:
        is_move = is_move_action(self.action)
        is_other_move = is_move_action(other.action)
        is_unit_heavy = self.unit.is_heavy()
        is_other_unit_heavy = other.unit.is_heavy()
        return is_move == is_other_move and is_unit_heavy == is_other_unit_heavy


def is_position_in_map(pos: np.ndarray, config: LuxEnvConfig) -> bool:
    return (0 <= pos[0] < config.map_size) and (0 <= pos[1] < config.map_size)


def enqueued_action_from_obs(
    action_queue: List[np.ndarray], use_simplified_spaces: bool
) -> Optional[np.ndarray]:
    if len(action_queue) == 0:
        return None
    action = action_queue[0]
    action_type = action[0]
    if action_type == 0:
        return np.array(
            (action_type, action[1] - (1 if use_simplified_spaces else 0), -1, -1, -1)
        )
    elif action_type == 1:
        return np.array(
            (
                action_type,
                -1,
                action[1] - (1 if use_simplified_spaces else 0),
                action[2],
                -1,
            )
        )
    elif action_type == 2:
        return np.array((action_type, -1, -1, -1, action[2]))
    elif 3 <= action_type <= 5:
        return np.array((action_type, -1, -1, -1, -1))
    else:
        raise ValueError(f"action_type {action_type} not supported: {action}")


def actions_equal(action: np.ndarray, enqueued: Optional[np.ndarray]) -> bool:
    if enqueued is None:
        return action[0] == 5  # Recharge is equivalent to empty queue
    return bool(np.all(np.where(enqueued == -1, True, action == enqueued)))


def no_valid_unit_actions(
    unit: LuxUnit, action_mask: np.ndarray, map_size: int
) -> bool:
    return not np.any(
        action_mask[
            pos_to_idx(unit.pos, map_size),
            FACTORY_ACTION_ENCODED_SIZE : FACTORY_ACTION_ENCODED_SIZE
            + UNIT_ACTION_SIZES[0],
        ]
    )


def no_valid_factory_actions(
    factory: LuxFactory, action_mask: np.ndarray, map_size: int
) -> bool:
    return not np.any(
        action_mask[pos_to_idx(factory.pos, map_size), :FACTORY_ACTION_ENCODED_SIZE]
    )


def factory_at_pos(state: LuxGameState, pos: np.ndarray) -> Optional[LuxFactory]:
    factory_idx = state.board.factory_occupancy_map[pos[0], pos[1]]
    if factory_idx == -1:
        return None
    factory_id = f"factory_{factory_idx}"
    if factory_id in state.factories["player_0"]:
        return state.factories["player_0"][factory_id]
    else:
        return state.factories["player_1"][factory_id]


def min_factory_resources(cfg: LuxEnvConfig) -> np.ndarray:
    return np.array([0, 0, cfg.INIT_WATER_METAL_PER_FACTORY, 0, 0])
