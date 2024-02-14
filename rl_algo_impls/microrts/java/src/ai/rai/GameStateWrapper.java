package ai.rai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import javax.lang.model.type.UnionType;

import rts.GameState;
import rts.PhysicalGameState;
import rts.Player;
import rts.PlayerAction;
import rts.ResourceUsage;
import rts.UnitAction;
import rts.UnitActionAssignment;
import rts.units.Unit;
import rts.units.UnitType;
import rts.units.UnitTypeTable;
import util.Pair;

import ai.abstraction.pathfinding.AStarPathFinding;
import ai.rai.util.Pos;

import static rts.PhysicalGameState.TERRAIN_WALL;

public class GameStateWrapper {
    GameState gs;
    PhysicalGameState _pgs;
    UnitTypeTable _utt = null;

    int debugLevel;
    ResourceUsage base_ru;
    AStarPathFinding astarPath;

    List<Unit> _resources = new ArrayList<>();

    List<Unit> _bases = new ArrayList<>();
    List<Unit> _barracks = new ArrayList<>();
    List<Unit> _workers = new ArrayList<>();
    List<Unit> _heavies = new ArrayList<>();
    List<Unit> _archers = new ArrayList<>();
    List<Unit> _lights = new ArrayList<>();
    List<Unit> _allyUnits = new ArrayList<>();
    List<Unit> _allyCombat = new ArrayList<>();

    List<Unit> _enemyBases = new ArrayList<>();
    List<Unit> _enemyBarracks = new ArrayList<>();
    List<Unit> _enemyWorkers = new ArrayList<>();
    List<Unit> _enemyHeavies = new ArrayList<>();
    List<Unit> _enemyArchers = new ArrayList<>();
    List<Unit> _enemyLights = new ArrayList<>();
    List<Unit> _enemies = new ArrayList<>();
    List<Unit> _enemiesCombat = new ArrayList<>();

    HashMap<Unit, Integer> _newDmgs = new HashMap<>();

    int _resourcesUsed = 0;
    List<Pos> _futureBarracks = new ArrayList<>();
    int _futureHeavies = 0;
    int _enemyFutureHeavy = 0;

    List<Integer> _dirs;
    int NoDirection = 100; // this is a hack

    static Player _p;
    static Player _enemyP;

    int[][][][] vectorObservation;
    public static final int numVectorObservationFeatureMaps = 13;
    public static final int numArrayObservationFeatureMaps = 2 + numVectorObservationFeatureMaps - 1;
    int[][][][] masks;

    public GameStateWrapper(GameState a_gs) {
        this(a_gs, 0);
    }

    public GameStateWrapper(GameState a_gs, int a_debugLevel) {
        gs = a_gs;
        debugLevel = a_debugLevel;

        base_ru = new ResourceUsage();
        _pgs = gs.getPhysicalGameState();
        _utt = gs.getUnitTypeTable();

        astarPath = new AStarPathFinding();

        if (_p != null) {
            _enemyP = gs.getPlayer(_p.getID() == 0 ? 1 : 0);
        }
        for (Unit u : _pgs.getUnits()) {
            UnitActionAssignment uaa = gs.getActionAssignment(u);

            if (uaa != null) {
                ResourceUsage ru = uaa.action.resourceUsage(u, _pgs);
                base_ru.merge(ru);
            }
            if (u.getType().isResource)
                _resources.add(u);
            else if (u.getType() == _utt.getUnitType("Base") && _p != null && isEnemyUnit(u))
                _enemyBases.add(u);
            else if (u.getType() == _utt.getUnitType("Base"))
                _bases.add(u);
            else if (u.getType() == _utt.getUnitType("Barracks") && _p != null && isEnemyUnit(u))
                _enemyBarracks.add(u);
            else if (u.getType() == _utt.getUnitType("Barracks"))
                _barracks.add(u);
            else if (u.getType() == _utt.getUnitType("Worker") && _p != null && isEnemyUnit(u))
                _enemyWorkers.add(u);
            else if (u.getType() == _utt.getUnitType("Worker"))
                _workers.add(u);
            else if (u.getType() == _utt.getUnitType("Ranged") && _p != null && isEnemyUnit(u))
                _enemyArchers.add(u);
            else if (u.getType() == _utt.getUnitType("Ranged"))
                _archers.add(u);
            else if (u.getType() == _utt.getUnitType("Heavy") && _p != null && isEnemyUnit(u))
                _enemyHeavies.add(u);
            else if (u.getType() == _utt.getUnitType("Heavy"))
                _heavies.add(u);
            else if (u.getType() == _utt.getUnitType("Light") && _p != null && isEnemyUnit(u))
                _enemyLights.add(u);
            else if (u.getType() == _utt.getUnitType("Light"))
                _lights.add(u);

            if (_p != null && isEnemyUnit(u)) {
                _enemies.add(u);
            }
            if (_p != null && isEnemyUnit(u) && u.getType().canAttack)
                _enemiesCombat.add(u);
            else if (u.getType().canAttack)
                _allyCombat.add(u);
        }

        _dirs = new ArrayList<>();
        _dirs.add(UnitAction.DIRECTION_UP);
        _dirs.add(UnitAction.DIRECTION_DOWN);
        _dirs.add(UnitAction.DIRECTION_LEFT);
        _dirs.add(UnitAction.DIRECTION_RIGHT);
    }

    /**
     * Constructs a vector observation for a player
     * 
     * |Idx| Observation Features | Max | Values
     * |---|-------------------|------------------------------------------------------------------|
     * | 0 | Hit Points | 10
     * | 1 | Resources | 40
     * | 2 | Owner | 3 | -, player 1, player 2
     * | 3 | Unit Types | 9 | -, resource, base, barrack, worker, light, heavy,
     * ranged, pending
     * | 4 | Current Action | 6 | -, move, harvest, return, produce, attack
     * | 5 | Move Parameter | 5 | -, north, east, south, west
     * | 6 | Harvest Parameter | 5 | -, north, east, south, west
     * | 7 | Return Parameter | 5 | -, north, east, south, west
     * | 8 | Produce Direction Parameter | 5 | -, north, east, south, west
     * | 9 | Produce Type Parameter | 8 | -, resource, base, barrack, worker, light,
     * heavy, ranged
     * |10 | Relative Attack Position | 6 | -, north, east, south, west, ranged
     * |11 | ETA | -128 - +127 | ETA can be up to 200, so offset by -128 to fit
     * |12 | Terrain | 2 | empty, wall
     * 
     * @param player
     * @return a vector observation for the specified player
     */
    public int[][][] getVectorObservation(int player) {
        PhysicalGameState pgs = gs.getPhysicalGameState();
        int height = pgs.getHeight();
        int width = pgs.getWidth();
        if (vectorObservation == null) {
            vectorObservation = new int[2][numVectorObservationFeatureMaps][height][width];
        }

        for (int f = 0; f < numVectorObservationFeatureMaps; ++f) {
            int default_v;
            if (f == 2 || f == 3) {
                default_v = -1;
            } else if (f == 11) {
                default_v = -128;
            } else {
                default_v = 0;
            }
            for (int y = 0; y < vectorObservation[player][0].length; y++) {
                Arrays.fill(vectorObservation[player][f][y], default_v);
            }
        }

        List<Unit> units = pgs.getUnits();
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            UnitActionAssignment uaa = gs.getActionAssignment(u);
            vectorObservation[player][0][u.getY()][u.getX()] = u.getHitPoints();
            vectorObservation[player][1][u.getY()][u.getX()] = u.getResources();
            int unitPlayer = u.getPlayer();
            if (unitPlayer != -1) {
                vectorObservation[player][2][u.getY()][u.getX()] = (unitPlayer + player) % 2;
            }
            vectorObservation[player][3][u.getY()][u.getX()] = u.getType().ID;
            if (uaa != null) {
                int type = uaa.action.getType();
                vectorObservation[player][4][u.getY()][u.getX()] = type;
                switch (type) {
                    case UnitAction.TYPE_NONE: {
                        break;
                    }
                    case UnitAction.TYPE_MOVE: {
                        vectorObservation[player][5][u.getY()][u.getX()] = uaa.action.getDirection() + 1;
                        break;
                    }
                    case UnitAction.TYPE_HARVEST: {
                        vectorObservation[player][6][u.getY()][u.getX()] = uaa.action.getDirection() + 1;
                        break;
                    }
                    case UnitAction.TYPE_RETURN: {
                        vectorObservation[player][7][u.getY()][u.getX()] = uaa.action.getDirection() + 1;
                        break;
                    }
                    case UnitAction.TYPE_PRODUCE: {
                        vectorObservation[player][8][u.getY()][u.getX()] = uaa.action.getDirection() + 1;
                        vectorObservation[player][9][u.getY()][u.getX()] = uaa.action.getUnitType().ID + 1;
                    }
                    case UnitAction.TYPE_ATTACK_LOCATION: {
                        int relativeX = uaa.action.getLocationX() - u.getX();
                        int relativeY = uaa.action.getLocationY() - u.getY();
                        int attackLoc = 4;
                        if (relativeX == 0) {
                            if (relativeY == -1) {
                                attackLoc = UnitAction.DIRECTION_UP;
                            } else if (relativeY == 1) {
                                attackLoc = UnitAction.DIRECTION_DOWN;
                            }
                        } else if (relativeY == 0) {
                            if (relativeX == -1) {
                                attackLoc = UnitAction.DIRECTION_LEFT;
                            } else if (relativeX == 1) {
                                attackLoc = UnitAction.DIRECTION_RIGHT;
                            }
                        }
                        vectorObservation[player][10][u.getY()][u.getX()] = attackLoc + 1;
                    }
                }
                int timestepsToCompletion = uaa.time + uaa.action.ETA(u) - gs.getTime();
                vectorObservation[player][11][u.getY()][u.getX()] = byteClampValue(timestepsToCompletion);
            } else {
                vectorObservation[player][4][u.getY()][u.getX()] = UnitAction.TYPE_NONE;
            }
        }

        // normalize by getting rid of -1
        for (int i = 0; i < vectorObservation[player][2].length; i++) {
            for (int j = 0; j < vectorObservation[player][2][i].length; j++) {
                vectorObservation[player][3][i][j] += 1;
                vectorObservation[player][2][i][j] += 1;
            }
        }

        // Terrain
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                vectorObservation[player][12][y][x] = pgs.getTerrain(x, y);
            }
        }

        // Spots reserved by other unit actions
        for (Integer pos : base_ru.getPositionsUsed()) {
            int y = pos / pgs.getWidth();
            int x = pos % pgs.getWidth();
            vectorObservation[player][3][y][x] = 8;
        }

        return vectorObservation[player];
    }

    public byte[] getTerrain() {
        PhysicalGameState pgs = gs.getPhysicalGameState();
        int height = pgs.getHeight();
        int width = pgs.getWidth();

        byte walls[] = new byte[height * width];
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                walls[y * width + x] = (byte) pgs.getTerrain(x, y);
            }
        }
        return walls;
    }

    /**
     * Constructs an array observation for a player (length number of units)
     * 
     * |Idx| Observation Features | Max | Values
     * |---|-------------------|------------------------------------------------------------------|
     * | 0 | y | 128 | Java's bytes are signed, while Python's are unsigned
     * | 1 | x | 128 |
     * | 2 | Hit Points | 10
     * | 3 | Resources | 40
     * | 4 | Owner | 3 | -, player 1, player 2
     * | 5 | Unit Types | 9 | -, resource, base, barrack, worker, light, heavy,
     * ranged, pending
     * | 6 | Current Action | 6 | -, move, harvest, return, produce, attack
     * | 7 | Move Parameter | 5 | -, north, east, south, west
     * | 8 | Harvest Parameter | 5 | -, north, east, south, west
     * | 9 | Return Parameter | 5 | -, north, east, south, west
     * |10 | Produce Direction Parameter | 5 | -, north, east, south, west
     * |11 | Produce Type Parameter | 8 | -, resource, base, barrack, worker, light,
     * heavy, ranged
     * |12 | Relative Attack Position | 6 | -, north, east, south, west, ranged
     * |13 | ETA | -128 - +127 | ETA can be up to 200, so offset by -128 to fit
     * 
     * @param player
     * @return a vector observation for the specified player
     */
    public byte[] getArrayObservation(int player) {
        PhysicalGameState pgs = gs.getPhysicalGameState();
        List<Unit> units = pgs.getUnits();
        List<Integer> positionsUsed = base_ru.getPositionsUsed();
        byte arrayObs[] = new byte[(units.size() + positionsUsed.size()) * numArrayObservationFeatureMaps];
        for (int i = 0; i < units.size(); ++i) {
            Unit u = units.get(i);
            int idx = i * numArrayObservationFeatureMaps;
            arrayObs[idx + 0] = (byte) u.getY();
            arrayObs[idx + 1] = (byte) u.getX();
            arrayObs[idx + 2] = (byte) u.getHitPoints();
            arrayObs[idx + 3] = (byte) u.getResources();
            int unitPlayer = u.getPlayer();
            if (unitPlayer != -1) {
                arrayObs[idx + 4] = (byte) ((unitPlayer + player) % 2 + 1);
            }
            arrayObs[idx + 5] = (byte) (u.getType().ID + 1);
            UnitActionAssignment uaa = gs.getActionAssignment(u);
            if (uaa != null) {
                byte type = (byte) uaa.action.getType();
                arrayObs[idx + 6] = type;
                switch (type) {
                    case UnitAction.TYPE_NONE: {
                        break;
                    }
                    case UnitAction.TYPE_MOVE: {
                        arrayObs[idx + 7] = (byte) (uaa.action.getDirection() + 1);
                        break;
                    }
                    case UnitAction.TYPE_HARVEST: {
                        arrayObs[idx + 8] = (byte) (uaa.action.getDirection() + 1);
                        break;
                    }
                    case UnitAction.TYPE_RETURN: {
                        arrayObs[idx + 9] = (byte) (uaa.action.getDirection() + 1);
                        break;
                    }
                    case UnitAction.TYPE_PRODUCE: {
                        arrayObs[idx + 10] = (byte) (uaa.action.getDirection() + 1);
                        arrayObs[idx + 11] = (byte) (uaa.action.getUnitType().ID + 1);
                    }
                    case UnitAction.TYPE_ATTACK_LOCATION: {
                        int relativeX = uaa.action.getLocationX() - u.getX();
                        int relativeY = uaa.action.getLocationY() - u.getY();
                        int attackLoc = 4;
                        if (relativeX == 0) {
                            if (relativeY == -1) {
                                attackLoc = UnitAction.DIRECTION_UP;
                            } else if (relativeY == 1) {
                                attackLoc = UnitAction.DIRECTION_DOWN;
                            }
                        } else if (relativeY == 0) {
                            if (relativeX == -1) {
                                attackLoc = UnitAction.DIRECTION_LEFT;
                            } else if (relativeX == 1) {
                                attackLoc = UnitAction.DIRECTION_RIGHT;
                            }
                        }
                        arrayObs[idx + 12] = (byte) (attackLoc + 1);
                    }
                }
                int timestepsToCompletion = uaa.time + uaa.action.ETA(u) - gs.getTime();
                arrayObs[idx + 13] = byteClampValue(timestepsToCompletion);
            } else {
                arrayObs[idx + 6] = (byte) UnitAction.TYPE_NONE;
                arrayObs[idx + 13] = byteClampValue(0);
            }
        }

        // Spots reserved by other unit actions
        for (int i = 0; i < positionsUsed.size(); ++i) {
            Integer pos = positionsUsed.get(i);
            int idx = (i + units.size()) * numArrayObservationFeatureMaps;
            int y = pos / pgs.getWidth();
            int x = pos % pgs.getWidth();
            arrayObs[idx + 0] = (byte) y;
            arrayObs[idx + 1] = (byte) x;
            arrayObs[idx + 5] = (byte) 8;
            arrayObs[idx + 13] = byteClampValue(0);
        }

        return arrayObs;
    }

    public int[][][] getMasks(int player) {
        UnitTypeTable utt = gs.getUnitTypeTable();
        PhysicalGameState pgs = gs.getPhysicalGameState();

        int maxAttackDiameter = utt.getMaxAttackRange() * 2 + 1;
        if (masks == null) {
            masks = new int[2][pgs.getHeight()][pgs.getWidth()][1 + 6 + 4 + 4 + 4 + 4 + utt.getUnitTypes().size()
                    + maxAttackDiameter * maxAttackDiameter];
        }

        Arrays.stream(masks[player]).forEach(mY -> Arrays.stream(mY).forEach(
                mX -> Arrays.fill(mX, 1)));

        for (Unit u : pgs.getUnits()) {
            final UnitActionAssignment uaa = gs.getActionAssignment(u);
            if (u.getPlayer() == player && uaa == null) {
                masks[player][u.getY()][u.getX()][0] = 1;
                getValidActionArray(u, utt, masks[player][u.getY()][u.getX()], maxAttackDiameter, 1);
            }
        }

        return masks[player];
    }

    public byte[] getBinaryMask(int player) {
        UnitTypeTable utt = gs.getUnitTypeTable();
        PhysicalGameState pgs = gs.getPhysicalGameState();
        List<Unit> units = pgs.getUnits().stream()
                .filter(u -> u.getPlayer() == player && gs.getActionAssignment(u) == null).collect(Collectors.toList());

        int maxAttackDiameter = utt.getMaxAttackRange() * 2 + 1;
        final int maskSize = 6 + 4 + 4 + 4 + 4 + utt.getUnitTypes().size()
                + maxAttackDiameter * maxAttackDiameter;
        byte byteMask[] = new byte[units.size() * (2 + maskSize)];
        int unitMask[] = new int[maskSize];
        for (int i = 0; i < units.size(); ++i) {
            Unit u = units.get(i);
            int idx = i * (2 + maskSize);
            byteMask[idx + 0] = (byte) u.getY();
            byteMask[idx + 1] = (byte) u.getX();

            getValidActionArray(u, utt, unitMask, maxAttackDiameter, 0);
            if (debugLevel > 0) {
                int baseUnitMask[] = new int[maskSize];
                UnitAction.getValidActionArray(u, gs, utt, baseUnitMask, maxAttackDiameter, 0);
                for (int j = 0; j < unitMask.length; ++j) {
                    if (unitMask[j] != 0 && baseUnitMask[j] == 0) {
                        System.err.println(
                                "Action mask for unit " + u + " is true for index " + j + " despite base mask not.");
                    }
                }
            }
            for (int j = 0; j < unitMask.length; ++j) {
                byteMask[idx + 2 + j] = (byte) unitMask[j];
            }

            Arrays.fill(unitMask, 0);
        }

        return byteMask;
    }

    public void getValidActionArray(Unit u, UnitTypeTable utt, int[] mask, int maxAttackRange,
            int idxOffset) {
        _p = gs.getPlayer(u.getPlayer());

        final List<UnitAction> uas = getUnitActions(u, utt);
        int centerCoordinate = maxAttackRange / 2;
        int numUnitTypes = utt.getUnitTypes().size();
        for (UnitAction ua : uas) {

            mask[idxOffset + ua.getType()] = 1;
            switch (ua.getType()) {
                case UnitAction.TYPE_NONE: {
                    break;
                }
                case UnitAction.TYPE_MOVE: {
                    mask[idxOffset + UnitAction.NUMBER_OF_ACTION_TYPES + ua.getDirection()] = 1;
                    break;
                }
                case UnitAction.TYPE_HARVEST: {
                    // +4 offset --> slots for movement directions
                    mask[idxOffset + UnitAction.NUMBER_OF_ACTION_TYPES + 4 + ua.getDirection()] = 1;
                    break;
                }
                case UnitAction.TYPE_RETURN: {
                    // +4+4 offset --> slots for movement and harvest directions
                    mask[idxOffset + UnitAction.NUMBER_OF_ACTION_TYPES + 4 + 4 + ua.getDirection()] = 1;
                    break;
                }
                case UnitAction.TYPE_PRODUCE: {
                    // +4+4+4 offset --> slots for movement, harvest, and resource-return directions
                    mask[idxOffset + UnitAction.NUMBER_OF_ACTION_TYPES + 4 + 4 + 4 + ua.getDirection()] = 1;
                    // +4+4+4+4 offset --> slots for movement, harvest, resource-return, and
                    // unit-produce directions
                    mask[idxOffset + UnitAction.NUMBER_OF_ACTION_TYPES + 4 + 4 + 4 + 4 + ua.getUnitType().ID] = 1;
                    break;
                }
                case UnitAction.TYPE_ATTACK_LOCATION: {
                    int relative_x = ua.getLocationX() - u.getX();
                    int relative_y = ua.getLocationY() - u.getY();
                    // +4+4+4+4 offset --> slots for movement, harvest, resource-return, and
                    // unit-produce directions
                    mask[idxOffset + UnitAction.NUMBER_OF_ACTION_TYPES + 4 + 4 + 4 + 4 + numUnitTypes
                            + (centerCoordinate + relative_y) * maxAttackRange + (centerCoordinate + relative_x)] = 1;
                    break;
                }
            }
        }
    }

    public List<UnitAction> getUnitActions(Unit unit, UnitTypeTable utt) {
        List<UnitAction> l = new ArrayList<>();

        PhysicalGameState pgs = gs.getPhysicalGameState();
        int player = unit.getPlayer();
        Player p = pgs.getPlayer(player);

        int x = unit.getX();
        int y = unit.getY();
        // retrieves units around me
        Unit uup = null, uright = null, udown = null, uleft = null;
        UnitActionAssignment uaaUp = null, uaaRight = null, uaaDown = null, uaaLeft = null;
        for (Unit u : pgs.getUnits()) {
            if (u.getX() == x) {
                if (u.getY() == y - 1) {
                    uup = u;
                    uaaUp = gs.getActionAssignment(uup);
                } else if (u.getY() == y + 1) {
                    udown = u;
                    uaaDown = gs.getActionAssignment(udown);
                }
            } else {
                if (u.getY() == y) {
                    if (u.getX() == x - 1) {
                        uleft = u;
                        uaaLeft = gs.getActionAssignment(uleft);
                    } else if (u.getX() == x + 1) {
                        uright = u;
                        uaaRight = gs.getActionAssignment(uright);
                    }
                }
            }
        }

        UnitType type = unit.getType();
        // if this unit can attack, adds an attack action for each unit around it
        if (type.canAttack) {
            // int attackTime = type.attackTime;
            // if (type.attackRange == 1) {
            // if (y > 0 && uup != null && uup.getPlayer() != player && uup.getPlayer() >= 0
            // && !isUnitMovingWithinTimesteps(uaaUp, attackTime)) {
            // l.add(new UnitAction(UnitAction.TYPE_ATTACK_LOCATION, uup.getX(),
            // uup.getY()));
            // }
            // if (x < pgs.getWidth() - 1 && uright != null && uright.getPlayer() != player
            // && uright.getPlayer() >= 0 && !isUnitMovingWithinTimesteps(uaaRight,
            // attackTime)) {
            // l.add(new UnitAction(UnitAction.TYPE_ATTACK_LOCATION, uright.getX(),
            // uright.getY()));
            // }
            // if (y < pgs.getHeight() - 1 && udown != null && udown.getPlayer() != player
            // && udown.getPlayer() >= 0
            // && !isUnitMovingWithinTimesteps(uaaDown, attackTime)) {
            // l.add(new UnitAction(UnitAction.TYPE_ATTACK_LOCATION, udown.getX(),
            // udown.getY()));
            // }
            // if (x > 0 && uleft != null && uleft.getPlayer() != player &&
            // uleft.getPlayer() >= 0
            // && !isUnitMovingWithinTimesteps(uaaLeft, attackTime)) {
            // l.add(new UnitAction(UnitAction.TYPE_ATTACK_LOCATION, uleft.getX(),
            // uleft.getY()));
            // }
            // } else {
            // int sqrange = type.attackRange * type.attackRange;
            // for (Unit u : pgs.getUnits()) {
            // if (u.getPlayer() < 0 || u.getPlayer() == player) {
            // continue;
            // }
            // int sq_dx = (u.getX() - x) * (u.getX() - x);
            // int sq_dy = (u.getY() - y) * (u.getY() - y);
            // if (sq_dx + sq_dy <= sqrange) {
            // UnitActionAssignment uaa = gs.getActionAssignment(u);
            // if (!isUnitMovingWithinTimesteps(uaa, attackTime))
            // l.add(new UnitAction(UnitAction.TYPE_ATTACK_LOCATION, u.getX(), u.getY()));
            // }
            // }
            // }

            UnitAction ua = goCombat(unit);
            if (ua != null)
                l.add(ua);
        }

        int resources = unit.getResources();
        // if this unit can harvest, adds a harvest action for each resource around it
        // if it is already carrying resources, adds a return action for each allied
        // base around it
        if (type.canHarvest) {
            // // harvest:
            // if (resources == 0) {
            // if (y > 0 && uup != null && uup.getType().isResource) {
            // l.add(new UnitAction(UnitAction.TYPE_HARVEST, UnitAction.DIRECTION_UP));
            // }
            // if (x < pgs.getWidth() - 1 && uright != null && uright.getType().isResource)
            // {
            // l.add(new UnitAction(UnitAction.TYPE_HARVEST, UnitAction.DIRECTION_RIGHT));
            // }
            // if (y < pgs.getHeight() - 1 && udown != null && udown.getType().isResource) {
            // l.add(new UnitAction(UnitAction.TYPE_HARVEST, UnitAction.DIRECTION_DOWN));
            // }
            // if (x > 0 && uleft != null && uleft.getType().isResource) {
            // l.add(new UnitAction(UnitAction.TYPE_HARVEST, UnitAction.DIRECTION_LEFT));
            // }
            // }
            // // return:
            // if (resources > 0) {
            // if (y > 0 && uup != null && uup.getType().isStockpile && uup.getPlayer() ==
            // player) {
            // l.add(new UnitAction(UnitAction.TYPE_RETURN, UnitAction.DIRECTION_UP));
            // }
            // if (x < pgs.getWidth() - 1 && uright != null && uright.getType().isStockpile
            // && uright.getPlayer() == player) {
            // l.add(new UnitAction(UnitAction.TYPE_RETURN, UnitAction.DIRECTION_RIGHT));
            // }
            // if (y < pgs.getHeight() - 1 && udown != null && udown.getType().isStockpile
            // && udown.getPlayer() == player) {
            // l.add(new UnitAction(UnitAction.TYPE_RETURN, UnitAction.DIRECTION_DOWN));
            // }
            // if (x > 0 && uleft != null && uleft.getType().isStockpile &&
            // uleft.getPlayer() == player) {
            // l.add(new UnitAction(UnitAction.TYPE_RETURN, UnitAction.DIRECTION_LEFT));
            // }
            // }
            UnitAction ua = goHarvesting(unit);
            if (ua != null) {
                l.add(ua);
            }
        }

        // if the player has enough resources, adds a produce action for each type this
        // unit produces.
        // a produce action is added for each free tile around the producer
        for (UnitType ut : type.produces) {
            UnitAction ua = null;

            UnitType unitType = unit.getType();

            if (unitType != null && unitType.name == "Worker") {
                ua = workerAction(unit);
            } else if (unitType != null && unitType.name == "Barracks") {
                ua = barracksAction(unit);

            } else if (unitType != null && unitType.name == "Base") {
                ua = basesAction(unit);
            }

            if (ua != null) {
                l.add(ua);
            }
            // if (p.getResources() >= ut.cost + base_ru.getResourcesUsed(player)) {
            // int tup = (y > 0 ? pgs.getTerrain(x, y - 1) :
            // PhysicalGameState.TERRAIN_WALL);
            // int tright = (x < pgs.getWidth() - 1 ? pgs.getTerrain(x + 1, y) :
            // PhysicalGameState.TERRAIN_WALL);
            // int tdown = (y < pgs.getHeight() - 1 ? pgs.getTerrain(x, y + 1) :
            // PhysicalGameState.TERRAIN_WALL);
            // int tleft = (x > 0 ? pgs.getTerrain(x - 1, y) :
            // PhysicalGameState.TERRAIN_WALL);

            // if (tup == PhysicalGameState.TERRAIN_NONE && pgs.getUnitAt(x, y - 1) == null)
            // {
            // var ua = new UnitAction(UnitAction.TYPE_PRODUCE, UnitAction.DIRECTION_UP,
            // ut);
            // if (ua.resourceUsage(unit, pgs).consistentWith(base_ru, gs))
            // l.add(ua);
            // }
            // if (tright == PhysicalGameState.TERRAIN_NONE && pgs.getUnitAt(x + 1, y) ==
            // null) {
            // var ua = new UnitAction(UnitAction.TYPE_PRODUCE, UnitAction.DIRECTION_RIGHT,
            // ut);
            // if (ua.resourceUsage(unit, pgs).consistentWith(base_ru, gs))
            // l.add(ua);
            // }
            // if (tdown == PhysicalGameState.TERRAIN_NONE && pgs.getUnitAt(x, y + 1) ==
            // null) {
            // var ua = new UnitAction(UnitAction.TYPE_PRODUCE, UnitAction.DIRECTION_DOWN,
            // ut);
            // if (ua.resourceUsage(unit, pgs).consistentWith(base_ru, gs))
            // l.add(ua);
            // }
            // if (tleft == PhysicalGameState.TERRAIN_NONE && pgs.getUnitAt(x - 1, y) ==
            // null) {
            // var ua = new UnitAction(UnitAction.TYPE_PRODUCE, UnitAction.DIRECTION_LEFT,
            // ut);
            // if (ua.resourceUsage(unit, pgs).consistentWith(base_ru, gs))
            // l.add(ua);
            // }
            // }
        }

        // if the unit can move, adds a move action for each free tile around it
        if (type.canMove) {
            // int tup = (y > 0 ? pgs.getTerrain(x, y - 1) :
            // PhysicalGameState.TERRAIN_WALL);
            // int tright = (x < pgs.getWidth() - 1 ? pgs.getTerrain(x + 1, y) :
            // PhysicalGameState.TERRAIN_WALL);
            // int tdown = (y < pgs.getHeight() - 1 ? pgs.getTerrain(x, y + 1) :
            // PhysicalGameState.TERRAIN_WALL);
            // int tleft = (x > 0 ? pgs.getTerrain(x - 1, y) :
            // PhysicalGameState.TERRAIN_WALL);

            // if (tup == PhysicalGameState.TERRAIN_NONE && uup == null) {
            // var ua = new UnitAction(UnitAction.TYPE_MOVE, UnitAction.DIRECTION_UP);
            // if (ua.resourceUsage(unit, pgs).consistentWith(base_ru, gs))
            // l.add(ua);
            // }
            // if (tright == PhysicalGameState.TERRAIN_NONE && uright == null) {
            // var ua = new UnitAction(UnitAction.TYPE_MOVE, UnitAction.DIRECTION_RIGHT);
            // if (ua.resourceUsage(unit, pgs).consistentWith(base_ru, gs))
            // l.add(ua);
            // }
            // if (tdown == PhysicalGameState.TERRAIN_NONE && udown == null) {
            // var ua = new UnitAction(UnitAction.TYPE_MOVE, UnitAction.DIRECTION_DOWN);
            // if (ua.resourceUsage(unit, pgs).consistentWith(base_ru, gs))
            // l.add(ua);
            // }
            // if (tleft == PhysicalGameState.TERRAIN_NONE && uleft == null) {
            // var ua = new UnitAction(UnitAction.TYPE_MOVE, UnitAction.DIRECTION_LEFT);
            // if (ua.resourceUsage(unit, pgs).consistentWith(base_ru, gs))
            // l.add(ua);
            // }

            // if (unit != null) {
            // Unit closestR = Closest(toPos(unit), _resources);

            // if (closestR != null) {
            // UnitAction moveT = moveTowards(unit, toPos(closestR));

            // if (moveT != null && moveT.resourceUsage(unit, pgs).consistentWith(base_ru,
            // gs)) {
            // l.add(moveT);
            // }
            // }
            // }

        }

        // units can always stay idle:
        l.add(new UnitAction(UnitAction.TYPE_NONE, 1));

        return l;
    }

    public boolean isUnitMovingWithinTimesteps(UnitActionAssignment uaa, int timesteps) {
        if (uaa == null || uaa.action.getType() != UnitAction.TYPE_MOVE) {
            return false;
        }
        int timestepsToCompletion = uaa.time + uaa.action.ETA(uaa.unit) - gs.getTime();
        return timestepsToCompletion < timesteps;
    }

    public static byte byteClampValue(int v) {
        return (byte) (Math.max(0, Math.min(v, 255)) - 128);
    }

    public byte[] getPlayerResources(int player) {
        return new byte[] {
                (byte) gs.getPlayer(player).getResources(),
                (byte) gs.getPlayer(1 - player).getResources()
        };
    }

    public static int[][] toVectorAction(GameState gs, PlayerAction pa) {
        UnitTypeTable utt = gs.getUnitTypeTable();
        int centerCoordinate = utt.getMaxAttackRange();
        int maxAttackDiameter = utt.getMaxAttackRange() * 2 + 1;
        int width = gs.getPhysicalGameState().getWidth();

        ArrayList<int[]> vectorActions = new ArrayList<>();

        for (Pair<Unit, UnitAction> uua : pa.getActions()) {

            Unit u = uua.m_a;
            UnitAction ua = uua.m_b;

            if (!gs.getActionAssignment(u).action.equals(ua)) {
                System.out.println(u + " hasn't been issued " + ua + ". Skipping.");
                continue;
            }
            int[] va = new int[8];
            va[0] = u.getX() + u.getY() * width;
            va[1] = ua.getType();
            switch (ua.getType()) {
                case UnitAction.TYPE_NONE: {
                    break;
                }
                case UnitAction.TYPE_MOVE: {
                    va[2] = ua.getDirection();
                    break;
                }
                case UnitAction.TYPE_HARVEST: {
                    va[3] = ua.getDirection();
                    break;
                }
                case UnitAction.TYPE_RETURN: {
                    va[4] = ua.getDirection();
                    break;
                }
                case UnitAction.TYPE_PRODUCE: {
                    va[5] = ua.getDirection();
                    va[6] = ua.getUnitType().ID;
                    break;
                }
                case UnitAction.TYPE_ATTACK_LOCATION: {
                    int relativeX = ua.getLocationX() - u.getX();
                    int relativeY = ua.getLocationY() - u.getY();
                    va[7] = relativeX + centerCoordinate
                            + (relativeY + centerCoordinate) * maxAttackDiameter;
                    break;
                }
            }
            vectorActions.add(va);
        }
        return vectorActions.toArray(new int[0][]);
    }

    int distance(Pos a, Pos b) {
        if (a == null | b == null)
            return Integer.MAX_VALUE;
        int dx = a.getX() - b.getX();
        int dy = a.getY() - b.getY();
        return Math.abs(dx) + Math.abs(dy);
    }

    int distance(Unit a, Unit b) {
        if (a == null | b == null)
            return Integer.MAX_VALUE;
        return distance(toPos(a), toPos(b));
    }

    int distance(Unit a, Pos b) {
        return distance(toPos(a), b);
    }

    double squareDist(Pos p, Pos u) {
        int dx = p.getX() - u.getX();
        int dy = p.getY() - u.getY();
        return Math.sqrt(dx * dx + dy * dy);
    }

    List<Pos> allPosDist(Pos src, int dist) {
        List<Pos> poss = new ArrayList<>();
        int sx = src.getX();
        int sy = src.getY();

        for (int x = -dist; x <= dist; x++) {
            int y = dist - Math.abs(x);
            poss.add(new Pos(sx + x, sy + y));
            if (y != 0)
                poss.add(new Pos(sx + x, sy - y));
        }
        return poss;
    }

    List<Pos> allPosRange(Pos src, int range) {
        List<Pos> poss = new ArrayList<>();
        for (int r = 0; r <= range; r++)
            poss.addAll(allPosDist(src, r));
        return poss;
    }

    int minDistance(Pos p, List<Pos> poses) {
        int minDist = Integer.MAX_VALUE;
        for (Pos u : poses) {
            minDist = minDist < distance(p, u) ? minDist : distance(p, u);
        }
        return minDist;
    }

    Pos toPos(Unit u) {
        return new Pos(u.getX(), u.getY());
    }

    List<Pos> toPos(List<Unit> units) {
        List<Pos> poses = new ArrayList<>();
        for (Unit u : units) {
            poses.add(toPos(u));
        }
        return poses;
    }

    int toDir(Pos src, Pos dst) {
        int dx = dst.getX() - src.getX();
        int dy = dst.getY() - src.getY();
        int dirX = dx > 0 ? UnitAction.DIRECTION_RIGHT : UnitAction.DIRECTION_LEFT;
        int dirY = dy > 0 ? UnitAction.DIRECTION_DOWN : UnitAction.DIRECTION_UP;
        if (Math.abs(dx) > Math.abs(dy))
            return dirX;
        return dirY;
    }

    boolean isEnemyUnit(Unit u) {
        return u.getPlayer() >= 0 && u.getPlayer() != _p.getID(); // can be neither ally ot foe
    }

    boolean outOfBound(Pos p) {
        if (p.getX() < 0 || p.getY() < 0 || p.getX() >= _pgs.getWidth()
                || p.getY() >= _pgs.getHeight())
            return true;
        return false;
    }

    boolean posFree(int x, int y, int dir) {
        Pos pos = futurePos(x, y, dir);
        int rasterPos = pos.getX() + pos.getY() * _pgs.getWidth();
        // if(_locationsTaken.contains(rasterPos))
        // return false;
        if (_pgs.getUnitAt(pos.getX(), pos.getY()) != null)
            return false;
        if (_pgs.getTerrain(pos.getX(), pos.getY()) == TERRAIN_WALL)
            return false;
        return true;
    }

    Unit closest(Pos src, List<Unit> units) {
        if (units == null || units.isEmpty())
            return null;
        Unit closest = units.stream().min(Comparator.comparing(u -> distance(src, toPos(u)))).get();
        return closest;
    }

    Unit closest(Unit src, List<Unit> units) {
        return closest(toPos(src), units);
    }

    boolean isBlocked(Unit u, Pos p) {
        if (outOfBound(p) || _pgs.getTerrain(p.getX(), p.getY()) != PhysicalGameState.TERRAIN_NONE)
            return true;
        if (!posFree(p.getX(), p.getY(), 100))
            return true;
        Unit pu = _pgs.getUnitAt(p.getX(), p.getY());
        if (pu == null)
            return false;
        if (pu.getType().isResource)
            return true;
        if (!isEnemyUnit(pu))
            return true;
        // if (u.getType() == _utt.getUnitType("Worker")
        // && pu.getType() != _utt.getUnitType("Worker"))
        // return true;
        return false;
    }

    UnitAction findPath(Unit u, Pos dst, int maxDist) {
        int proximity[][] = new int[_pgs.getWidth()][_pgs.getHeight()];
        for (int[] row : proximity)
            Arrays.fill(row, Integer.MAX_VALUE);
        proximity[dst.getX()][dst.getY()] = 0;
        int dist = 1;
        List<Pos> markNext = allPosDist(dst, 1);
        while (!markNext.isEmpty() && dist <= maxDist) {
            List<Pos> queue = new ArrayList<>();
            for (Pos p : markNext) {
                if (isBlocked(u, p) || proximity[p.getX()][p.getY()] != Integer.MAX_VALUE)
                    continue;
                proximity[p.getX()][p.getY()] = dist;
                List<Pos> nn = allPosDist(p, 1);
                for (Pos n : nn) {
                    if (isBlocked(u, n) || proximity[n.getX()][n.getY()] != Integer.MAX_VALUE || queue.contains(n))
                        continue;
                    queue.add(n);
                }
            }
            if (proximity[u.getX()][u.getY()] != Integer.MAX_VALUE)
                break;
            dist += 1;
            markNext.clear();
            markNext.addAll(queue);
        }
        // now lets see if there is a path
        List<Pos> moves = allPosDist(toPos(u), 1);
        Integer bestFit = Integer.MIN_VALUE;
        Pos bestPos = null;
        for (Pos p : moves) {
            if (outOfBound(p) || _pgs.getTerrain(p.getX(), p.getY()) == TERRAIN_WALL)
                continue;
            if (proximity[p.getX()][p.getY()] == Integer.MAX_VALUE)
                continue;
            Unit pu = _pgs.getUnitAt(p.getX(), p.getY());
            if (pu != null)
                continue;
            int fit = -1000 * proximity[p.getX()][p.getY()] - (int) squareDist(p, dst);
            if (fit > bestFit) {
                bestFit = fit;
                bestPos = p;
            }
        }
        if (bestPos == null)
            return null;
        int dir = toDir(toPos(u), bestPos);
        return new UnitAction(UnitAction.TYPE_MOVE, dir);
    }

    UnitAction findPathAdjacent(Unit src, Integer dst) {
        int x = dst % _pgs.getWidth();
        int y = dst / _pgs.getWidth();
        Pos dstP = new Pos(x, y);

        UnitAction astarMove = astarPath.findPathToAdjacentPosition(src, dst, gs, base_ru);
        if (astarMove == null)
            return astarMove;

        int radius = _pgs.getUnits().size() > 32 ? 42 : 64;
        UnitAction ua = findPath(src, dstP, radius);
        if (ua != null)
            return ua;
        return astarMove;
    }

    Pos futurePos(int x, int y, int dir) {
        int nx = x;
        int ny = y;
        switch (dir) {
            case UnitAction.DIRECTION_DOWN:
                ny = (ny == _pgs.getHeight() - 1) ? ny : ny + 1;
                break;
            case UnitAction.DIRECTION_UP:
                ny = (ny == 0) ? ny : ny - 1;
                break;
            case UnitAction.DIRECTION_RIGHT:
                nx = (nx == _pgs.getWidth() - 1) ? nx : nx + 1;
                break;
            case UnitAction.DIRECTION_LEFT:
                nx = (nx == 0) ? nx : nx - 1;
                break;
            default:
                break;
        }
        return new Pos(nx, ny);
    }

    Pos futurePoss(Unit unit) {
        UnitActionAssignment aa = gs.getActionAssignment(unit);
        if (aa == null)
            return new Pos(unit.getX(), unit.getY());
        if (aa.action.getType() == UnitAction.TYPE_MOVE)
            return futurePos(unit.getX(), unit.getY(), aa.action.getDirection());
        return new Pos(unit.getX(), unit.getY());
    }

    boolean overPowering() {
        int power = 0;
        for (Unit u : _allyCombat)
            power += u.getMaxDamage();
        int ePower = 0;
        for (Unit u : _enemiesCombat)
            ePower += u.getMaxDamage();
        return (power - (int) 1.2 * ePower) > 0;
    }

    boolean busy(Unit u) {
        // if(_pa.getAction(u) != null)
        // return true;
        UnitActionAssignment aa = gs.getActionAssignment(u);
        return aa != null;
    }

    boolean dying(Unit u) {
        return u.getHitPoints() <= _newDmgs.getOrDefault(u, 0);
    }

    boolean validForFutureBuild(Pos p) {
        if (outOfBound(p) || _pgs.getTerrain(p.getX(), p.getY()) == TERRAIN_WALL)
            return false;
        Unit exUnit = _pgs.getUnitAt(p.getX(), p.getY());
        if (exUnit != null && (exUnit.getType() == _utt.getUnitType("Base")
                || exUnit.getType() == _utt.getUnitType("Barracks"))) // todo - may be if mobile unit too?
            return false;
        return true;
    }

    double buildBlockPenalty(Pos p, boolean diagonalsPenalty) {
        double blockingScore = 0;
        List<Pos> nn = allPosRange(p, 2);
        for (Pos n : nn) {
            int dist = distance(n, p);
            if ((!diagonalsPenalty && dist == 2) || dist == 0)
                continue;
            if (outOfBound(n) || _pgs.getTerrain(n.getX(), n.getY()) == TERRAIN_WALL)
                blockingScore += dist > 1 ? 0 : 0.2;
            Unit u = _pgs.getUnitAt(n.getX(), n.getY());
            if (u == null)
                continue;
            if (u.getType().isResource || u.getType() == _utt.getUnitType("Base"))
                blockingScore += dist > 1 ? 1 : 4;
        }
        return blockingScore;
    }

    boolean between(Pos a, Pos b, Pos c) {
        if (a.getX() < b.getX() && c.getX() < b.getX())
            return false;
        if (a.getX() > b.getX() && c.getX() > b.getX())
            return false;
        if (a.getY() < b.getY() && c.getY() < b.getY())
            return false;
        if (a.getY() > b.getY() && c.getY() > b.getY())
            return false;
        return true;
    }

    int buildBarrackWorkerScore(Pos dst, Unit w) {
        if (busy(w))
            return Integer.MIN_VALUE;
        int barrackTLen = _utt.getUnitType("Barracks").produceTime / _utt.getUnitType("Worker").moveTime;
        int heavyTLen = _utt.getUnitType("Heavy").produceTime / _utt.getUnitType("Worker").moveTime;
        int dangerTLen = barrackTLen + heavyTLen;

        Unit e = closest(dst, _enemies);
        int dangerPenalty = 0;

        if (e != null) {
            int edist = Math.max(distance(dst, toPos(e)), 1);
            if (edist < dangerTLen) {
                dangerPenalty = (2 * dangerTLen) / edist;
                if (between(toPos(w), dst, toPos(e)))
                    dangerPenalty -= 3; // building site is blocking the enemy
            }
        }
        int wDist = distance(toPos(w), dst) / 2;
        return -dangerPenalty - wDist;
    }

    int buildBarrackScore(Pos dst) {
        if (!validForFutureBuild(dst))
            return Integer.MIN_VALUE;
        if (_workers.isEmpty())
            return Integer.MIN_VALUE;

        Unit b = closest(dst, _bases);
        if (isSeperated(b, _enemies))
            return -(int) buildBlockPenalty(dst, true) * 10;

        List<Pos> allBrxs = toPos(_barracks);
        allBrxs.addAll(_futureBarracks);
        int deseretScore = 0;
        if (!allBrxs.isEmpty())
            deseretScore = (int) (minDistance(toPos(b), allBrxs) / 2); // like base to be deserted

        double blockingPenalty = buildBlockPenalty(dst, false);

        Unit worker = _workers.stream().max(Comparator.comparingInt((u) -> buildBarrackWorkerScore(dst, u))).get();
        int workerScore = buildBarrackWorkerScore(dst, worker); // include danger

        return 10 * (deseretScore - (int) blockingPenalty + workerScore);
    }

    int combatScore(Unit u, Unit e) {
        int score = -distance(u, e);

        if (u.getType() == _utt.getUnitType("Ranged")
                && e.getType() == _utt.getUnitType("Ranged") && _pgs.getWidth() > 9)
            score += 2; // todo may be change that and add logic below

        if (_pgs.getWidth() >= 16
                && (u.getType() == _utt.getUnitType("Heavy") || u.getType() == _utt.getUnitType("Ranged"))
                && (e.getType() == _utt.getUnitType("Barracks"))) // todo - remove? todo base
            score += _pgs.getWidth();

        return score;
    }

    int[] getCombatScores(Unit u, List<Unit> targets) {
        int[] scores = new int[targets.size()];
        int counter = 0;
        for (Unit t : targets) {
            scores[counter] = combatScore(u, t);
            counter++;
        }
        return scores;
    }

    // int moveTowards(Unit a, Pos e) {
    // int pos = e.getX() + e.getY() * pgs.getWidth();
    // UnitAction move = findPathAdjacent(a, pos);
    // if (move == null)
    // return -1;
    // if (!gs.isUnitActionAllowed(a, move))
    // return -1;
    // Pos futPos = futurePos(a.getX(), a.getY(), move.getDirection());
    // int fPos = futPos.getX() + futPos.getY() *
    // pgs.getWidth();
    // System.out.println(move + " moveTowards");

    // // if (_locationsTaken.contains(fPos))
    // // return false;
    // // _pa.addUnitAction(a, move);
    // // _locationsTaken.add(fPos);
    // return fPos;
    // }

    public UnitAction moveTowards(Unit a, Pos e) {
        int pos = e.getX() + e.getY() * _pgs.getWidth();
        UnitAction move = findPathAdjacent(a, pos);
        if (move == null)
            return null;
        if (!gs.isUnitActionAllowed(a, move))
            return null;

        return move;
    }

    ResourceUsage fullResourceUse() {
        ResourceUsage ru = gs.getResourceUsage().clone();
        // ru.merge(_pa.getResourceUsage());

        // todo - on small board taking future pos as used may
        // be to harsh and costly
        // for (Integer pos : _locationsTaken) {
        // int x = pos % _pgs.getWidth();
        // int y = pos / _pgs.getWidth();
        // Unit u = new Unit(0, _utt.getUnitType("Worker"), x, y);
        // UnitAction a = null;
        // if (x > 0)
        // a = new UnitAction(UnitAction.TYPE_MOVE, NoDirection); //this is a hack
        // else
        // a = new UnitAction(UnitAction.TYPE_MOVE, NoDirection);
        // UnitActionAssignment uaa = new UnitActionAssignment(u, a, 0);
        // ru.merge(uaa.action.resourceUsage(u, _pgs));
        // }
        return ru;
    }

    boolean isSeperated(Unit base, List<Unit> units) {
        for (Unit u : units) {
            int rasterPos = u.getX() + u.getY() * _pgs.getWidth();
            ResourceUsage rsu = fullResourceUse();// (_pgs.getHeight() == 8) ? _gs.getResourceUsage() : //todo - remove
                                                  // this
            if (astarPath.findPathToAdjacentPosition(base, rasterPos, gs, rsu) != null)
                return false;
        }
        return true;
    }

    boolean enemyHeaviesWeak() {
        if (_enemyFutureHeavy > 0)
            return false;
        if (_enemyHeavies.size() > 1)
            return false;

        if (_enemyHeavies.size() == 1) {
            if (_enemyHeavies.get(0).getHitPoints() > 3) // rangers get 3 shoots at heavy
                return false;
        }

        int totEnemyRes = _enemyP.getResources();
        for (Unit u : _enemyWorkers) {
            Pos uPos = new Pos(u.getX(), u.getY());
            int baseDist = minDistance(uPos, toPos(_enemyBases));
            int resDist = u.getResources() > 0 ? 0 : minDistance(uPos, toPos(_resources));

            // todo - here what matters is how close are we to attack relative to future
            // heavies
            totEnemyRes += (baseDist + resDist) < _pgs.getWidth() / 2 ? 1 : 0;
        }
        if (totEnemyRes >= _utt.getUnitType("Heavy").cost)
            return false;
        return true;
    }

    boolean shouldWorkersAttack() {
        if (_pgs.getWidth() <= 12)
            return true;
        if (enemyHeaviesWeak() && _enemyArchers.isEmpty() &&
                _heavies.isEmpty() && _futureHeavies == 0 && _archers.isEmpty())
            return true;
        return false; // todo here
    }

    int bestBuildWorkerDir(Unit base) {
        int bestScore = -Integer.MAX_VALUE;
        int bestDir = 0;
        for (int dir : _dirs) {
            int score = 0;
            Pos n = futurePos(base.getX(), base.getY(), dir);
            if (outOfBound(n) || _pgs.getTerrain(n.getX(), n.getY()) == TERRAIN_WALL)
                continue;
            Unit u = _pgs.getUnitAt(n.getX(), n.getY());
            if (u != null)
                continue;
            if (!posFree(n.getX(), n.getY(), dir))
                continue;
            Unit e = closest(base, _enemies);
            Unit r = closest(base, _resources);
            if (e == null) // already won?
                continue;
            // towards enemy, or
            if (r == null || _workers.size() >= 2 * _bases.size()) {// todo here *2?
                score = -distance(n, toPos(e)); // close to enemy is better
            } else
                score = -distance(n, toPos(r)); // close to resource
            if (score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }
        return bestDir;
    }

    int workerPerBase(Unit base) {
        if (_pgs.getWidth() < 9)// && _barracks.isEmpty())
            return 15;

        if (_pgs.getWidth() > 16)
            return 2;

        if (isSeperated(base, _enemies) || gs.getTime() > 1000)
            return 2;

        int enemyFromBelow = (_enemyWorkers.size()) / Math.max(_enemyBases.size(), 1);
        return Math.max(enemyFromBelow, 2);
        // return .size()
        // return 4;
    }

    UnitAction doNothing(Unit u) {
        return new UnitAction(UnitAction.TYPE_NONE, 1);
    }

    UnitAction returnHarvest(Unit worker, Unit base) {
        if (busy(worker))
            return null;
        if (distance(toPos(worker), toPos(base)) != 1) {
            System.out.println("wanted to return but the base is not nearby");
            return null;
        }
        int dir = toDir(toPos(worker), toPos(base));
        UnitAction ua = new UnitAction(UnitAction.TYPE_RETURN, dir);
        if (!gs.isUnitActionAllowed(worker, ua))
            return null;
        return ua;
    }

    UnitAction produceCombat(Unit barrack, UnitType unitType) {
        List<Integer> dirsLeft = new ArrayList<>(_dirs);
        while (!dirsLeft.isEmpty()) {
            int bestScore = -Integer.MAX_VALUE;
            int bestDir = -Integer.MAX_VALUE;
            for (Integer dir : dirsLeft) {
                Pos p = futurePos(barrack.getX(), barrack.getY(), dir);
                int score = -minDistance(p, toPos(_enemies));
                if (score > bestScore) {
                    bestScore = score;
                    bestDir = dir;
                }
            }
            if (bestDir == -Integer.MAX_VALUE)
                break;

            UnitAction ua = produce(barrack, bestDir, unitType);
            if (ua != null)
                return ua;
            dirsLeft.remove(Integer.valueOf(bestDir));
        }
        return null;
    }

    UnitAction barracksAction(Unit barrack) {
        UnitAction ua = null;
        if (busy(barrack))
            return ua;

        if (isSeperated(barrack, _enemies)) {
            ua = produceCombat(barrack, _utt.getUnitType("Ranged"));
            if (ua == null)
                return ua;
        }
        ua = produceCombat(barrack, _utt.getUnitType("Heavy"));
        if (ua == null)
            return ua;

        if (enemyHeaviesWeak()) // not enough resource for heavy
        {
            ua = produceCombat(barrack, _utt.getUnitType("Ranged"));
            if (ua == null)
                return ua;
        }

        if (_resources.isEmpty() && _p.getResources() - _resourcesUsed < _utt.getUnitType("Heavy").cost)
            return produceCombat(barrack, _utt.getUnitType("Ranged"));

        return ua;
    }

    UnitAction harvest(Unit worker, Unit resource) {
        if (busy(worker))
            return null;
        if (distance(toPos(worker), toPos(resource)) != 1) {
            System.out.println("wanted to harvest but the resource is not nearby");
            return null;
        }
        int dir = toDir(toPos(worker), toPos(resource));
        UnitAction ua = new UnitAction(UnitAction.TYPE_HARVEST, dir);
        if (gs.isUnitActionAllowed(worker, ua))
            return ua;
        return null;
    }

    UnitAction tryMoveAway(Unit a, Unit b) {
        int startDist = distance(toPos(a), toPos(b));
        List<Integer> dirsRand = new ArrayList<>(_dirs);
        Collections.shuffle(dirsRand);

        for (int dir : dirsRand) {
            Pos newPos = futurePos(a.getX(), a.getY(), dir);
            if (distance(newPos, toPos(b)) <= startDist)
                continue;
            if (!posFree(newPos.getX(), newPos.getY(), NoDirection)) // a hack
                continue;
            UnitAction ua = new UnitAction(UnitAction.TYPE_MOVE, dir);
            if (gs.isUnitActionAllowed(a, ua))
                return ua;
        }
        return null;
    }

    UnitAction attackNow(Unit a, Unit e) {
        UnitAction ua = new UnitAction(UnitAction.TYPE_ATTACK_LOCATION, e.getX(), e.getY());
        if (!gs.isUnitActionAllowed(a, ua))
            return null;

        // _pa.addUnitAction(a, ua);
        if (!_newDmgs.containsKey(e))
            _newDmgs.put(e, 0);
        int newDmg = _newDmgs.get(e) + a.getMaxHitPoints();
        _newDmgs.replace(e, newDmg);
        return ua;
    }

    UnitAction produce(Unit u, int dir, UnitType bType) {
        if (busy(u))
            return null;
        // if(_p.getResources() - _resourcesUsed < bType.cost)
        // return false;
        if (!posFree(u.getX(), u.getY(), dir))
            return null;
        UnitAction ua = new UnitAction(UnitAction.TYPE_PRODUCE, dir, bType);
        // if (!gs.isUnitActionAllowed(u, ua))
        // return null;
        // _pa.addUnitAction(u, ua);
        // lockPos(u.getX(), u.getY(), ua.getDirection());
        if (bType == _utt.getUnitType("Barracks"))
            _futureBarracks.add(futurePos(u.getX(), u.getY(), ua.getDirection()));
        else if (bType == _utt.getUnitType("Heavy"))
            _futureHeavies += 1;
        _resourcesUsed += bType.cost;
        return ua;
    }

    UnitAction moveInDirection(Unit a, Unit b) {
        int startDist = distance(toPos(a), toPos(b));
        List<Integer> dirsRand = new ArrayList<>(_dirs);
        Collections.shuffle(dirsRand);
        for (int dir : dirsRand) {
            Pos newPos = futurePos(a.getX(), a.getY(), dir);
            if (distance(newPos, toPos(b)) >= startDist)
                continue;
            if (!posFree(newPos.getX(), newPos.getY(), NoDirection)) // a hack
                continue;
            UnitAction ua = new UnitAction(UnitAction.TYPE_MOVE, dir);
            if (gs.isUnitActionAllowed(a, ua)) {
                // lockPos(newPos.getX(), newPos.getY(), NoDirection);
                return ua;
            }
        }
        return null;
    }

    UnitAction workerAction(Unit worker) {
        if (busy(worker))
            return null;

        UnitAction ua = buildBracks(worker);

        if (ua != null)
            return ua;

        if (worker.getResources() <= 0) {

            Unit resource = closest(worker, _resources);

            if (resource == null)
                return null;

            return moveTowards(worker, toPos(resource));
        }
        if (_bases == null)
            return null;

        Unit base = closest(worker, _bases);
        if (base == null)
            return null;
        else if (distance(worker, base) <= 1) {
            return returnHarvest(worker, base); // todo - check if safe?
        } else {
            return moveTowards(worker, toPos(base));

        }
    }

    UnitAction produceWherever(Unit u, UnitType bType) {
        for (int dir : _dirs) {
            UnitAction ua = produce(u, dir, bType);
            if (ua != null)
                return ua;
        }

        return null;
    }

    boolean needNewBarracks() {
        if (_barracks.size() + _futureBarracks.size() >= _bases.size()) // todo
            return false;

        int maxDist = _pgs.getWidth() / 4;
        for (Unit b : _bases) {
            int minDist = minDistance(toPos(b), _futureBarracks);
            if (minDist <= maxDist)
                continue;
            Unit brx = closest(b, _barracks);
            if (brx != null && distance(toPos(brx), toPos(b)) < maxDist)
                continue;
            return true;
        }
        return false;
    }

    UnitAction goBuildBarrack(Unit worker, Pos dst) {
        if (distance(toPos(worker), dst) != 1)
            return moveTowards(worker, dst);
        int dir = toDir(toPos(worker), dst);
        return produce(worker, dir, _utt.getUnitType("Barracks"));
    }

    UnitAction basesAction(Unit base) {
        int producingWorker = 0; // todo change logic
        long producingCount = _bases.stream().filter(b -> gs.getActionAssignment(b) != null).count();
        if (busy(base))
            return null;
        int workerPerBase = workerPerBase(base);
        boolean onlyOption = _resources.isEmpty() && ((_p.getResources() - _resourcesUsed) == 1); // todo some workers
                                                                                                  // carry...
        if (onlyOption) {
            return produceWherever(base, _utt.getUnitType("Worker"));
        }
        // Dont produce if not in abundance
        if (_pgs.getWidth() >= 9 && _workers.size() + producingWorker + producingCount >= workerPerBase * _bases.size())
            return null;
        int dirBuild = bestBuildWorkerDir(base);
        UnitAction succ = produce(base, dirBuild, _utt.getUnitType("Worker"));
        if (succ == null)
            return produceWherever(base, _utt.getUnitType("Worker"));
        return succ;
    }

    UnitAction goCombat(Unit u) {
        if (busy(u) || !u.getType().canAttack) {
            return null;
        }
        // continue;

        List<Unit> candidates = new ArrayList(_enemies);
        List<Unit> candidatesCopy = new ArrayList(candidates);
        int[] scores = getCombatScores(u, candidates);
        Collections.sort(candidates, Comparator.comparing(e -> -scores[candidatesCopy.indexOf(e)])); // - for
                                                                                                     // ascending
                                                                                                     // order
        int counter = 0;
        int cutOff = _enemiesCombat.size() > 24 ? 12 : 24; // for performance
        // long timeRemain = timeRemaining(true);
        if (!candidates.isEmpty())
            return null;

        while (counter < candidates.size() && counter < cutOff) {
            Unit enemy = candidates.get(counter);
            UnitAction mta = moveTowards(u, futurePoss(enemy));
            if (mta != null)
                break;

            counter++;
        }
        if (counter < candidates.size()) // if (!candidates.isEmpty()) //did we make a move
            return null;
        if (u.getType() != _utt.getUnitType("Ranged")) {
            return null;
        } // continue;
        Unit enemy = candidates.get(0);
        if (overPowering()) // give worker to open pathway if blocked
            return tryMoveAway(u, u);
        return moveInDirection(u, enemy);
    }

    UnitAction goHarvesting(Unit worker) {
        Unit closestRes = closest(worker, _resources);
        if (closestRes == null)
            return null;
        int dist = distance(toPos(worker), toPos(closestRes));
        if (dist == 1) {
            return harvest(worker, closestRes); // todo - safe to harvest
        }

        UnitAction ua = moveTowards(worker, toPos(closestRes));
        if (ua != null)
            tryMoveAway(worker, worker); // random move to shake things up
        return ua;
    }

    UnitAction buildBracks(Unit worker) {
        // if (_p.getResources() - _resourcesUsed - 1 <
        // _utt.getUnitType("Barracks").cost)
        // return null;

        if (!needNewBarracks())
            return null;

        if (_bases.isEmpty()) // todo
            return null;

        List<Pos> pCandidates = new ArrayList<>();
        for (Unit base : _bases) {
            List<Pos> poses = allPosRange(toPos(base), 2);
            pCandidates.addAll(poses);
        }

        int counter = 0;

        Pos c = pCandidates.stream().max(Comparator.comparingInt((e) -> buildBarrackScore(e))).get();

        if (c == null || buildBarrackScore(c) == Integer.MIN_VALUE
                || buildBarrackWorkerScore(c, worker) == Integer.MIN_VALUE)
            return null;
        return goBuildBarrack(worker, c);
    }
}