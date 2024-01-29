package ai.rai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

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
    int debugLevel;
    ResourceUsage base_ru;
    AStarPathFinding astarPath;

    List<Unit> _resources = new ArrayList<>();
    List<Unit> _enemies = new ArrayList<>();
    List<Unit> _enemiesCombat = new ArrayList<>();
    List<Unit> _allyCombat = new ArrayList<>();
    HashMap<Unit, Integer> _newDmgs = new HashMap<>();

    List<Integer> _dirs;
    int NoDirection = 100; // this is a hack

    static Player _p;

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
        for (Unit u : _pgs.getUnits()) {
            UnitActionAssignment uaa = gs.getActionAssignment(u);
            if (uaa != null) {
                ResourceUsage ru = uaa.action.resourceUsage(u, _pgs);
                base_ru.merge(ru);
            }

            if (u.getType().isResource)
                _resources.add(u);
            if (isEnemyUnit(u)) {
                _enemies.add(u);
            }
            if (isEnemyUnit(u) && u.getType().canAttack)
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
                mX -> Arrays.fill(mX, 0)));

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
            int attackTime = type.attackTime;
            if (type.attackRange == 1) {
                if (y > 0 && uup != null && uup.getPlayer() != player && uup.getPlayer() >= 0
                        && !isUnitMovingWithinTimesteps(uaaUp, attackTime)) {
                    l.add(new UnitAction(UnitAction.TYPE_ATTACK_LOCATION, uup.getX(), uup.getY()));
                }
                if (x < pgs.getWidth() - 1 && uright != null && uright.getPlayer() != player
                        && uright.getPlayer() >= 0 && !isUnitMovingWithinTimesteps(uaaRight, attackTime)) {
                    l.add(new UnitAction(UnitAction.TYPE_ATTACK_LOCATION, uright.getX(), uright.getY()));
                }
                if (y < pgs.getHeight() - 1 && udown != null && udown.getPlayer() != player && udown.getPlayer() >= 0
                        && !isUnitMovingWithinTimesteps(uaaDown, attackTime)) {
                    l.add(new UnitAction(UnitAction.TYPE_ATTACK_LOCATION, udown.getX(), udown.getY()));
                }
                if (x > 0 && uleft != null && uleft.getPlayer() != player && uleft.getPlayer() >= 0
                        && !isUnitMovingWithinTimesteps(uaaLeft, attackTime)) {
                    l.add(new UnitAction(UnitAction.TYPE_ATTACK_LOCATION, uleft.getX(), uleft.getY()));
                }
            } else {
                int sqrange = type.attackRange * type.attackRange;
                for (Unit u : pgs.getUnits()) {
                    if (u.getPlayer() < 0 || u.getPlayer() == player) {
                        continue;
                    }
                    int sq_dx = (u.getX() - x) * (u.getX() - x);
                    int sq_dy = (u.getY() - y) * (u.getY() - y);
                    if (sq_dx + sq_dy <= sqrange) {
                        UnitActionAssignment uaa = gs.getActionAssignment(u);
                        if (!isUnitMovingWithinTimesteps(uaa, attackTime))
                            l.add(new UnitAction(UnitAction.TYPE_ATTACK_LOCATION, u.getX(), u.getY()));
                    }
                }
            }
        }

        int resources = unit.getResources();
        // if this unit can harvest, adds a harvest action for each resource around it
        // if it is already carrying resources, adds a return action for each allied
        // base around it
        if (type.canHarvest) {
            // harvest:
            if (resources == 0) {
                if (y > 0 && uup != null && uup.getType().isResource) {
                    l.add(new UnitAction(UnitAction.TYPE_HARVEST, UnitAction.DIRECTION_UP));
                }
                if (x < pgs.getWidth() - 1 && uright != null && uright.getType().isResource) {
                    l.add(new UnitAction(UnitAction.TYPE_HARVEST, UnitAction.DIRECTION_RIGHT));
                }
                if (y < pgs.getHeight() - 1 && udown != null && udown.getType().isResource) {
                    l.add(new UnitAction(UnitAction.TYPE_HARVEST, UnitAction.DIRECTION_DOWN));
                }
                if (x > 0 && uleft != null && uleft.getType().isResource) {
                    l.add(new UnitAction(UnitAction.TYPE_HARVEST, UnitAction.DIRECTION_LEFT));
                }
            }
            // return:
            if (resources > 0) {
                if (y > 0 && uup != null && uup.getType().isStockpile && uup.getPlayer() == player) {
                    l.add(new UnitAction(UnitAction.TYPE_RETURN, UnitAction.DIRECTION_UP));
                }
                if (x < pgs.getWidth() - 1 && uright != null && uright.getType().isStockpile
                        && uright.getPlayer() == player) {
                    l.add(new UnitAction(UnitAction.TYPE_RETURN, UnitAction.DIRECTION_RIGHT));
                }
                if (y < pgs.getHeight() - 1 && udown != null && udown.getType().isStockpile
                        && udown.getPlayer() == player) {
                    l.add(new UnitAction(UnitAction.TYPE_RETURN, UnitAction.DIRECTION_DOWN));
                }
                if (x > 0 && uleft != null && uleft.getType().isStockpile && uleft.getPlayer() == player) {
                    l.add(new UnitAction(UnitAction.TYPE_RETURN, UnitAction.DIRECTION_LEFT));
                }
            }
        }

        // if the player has enough resources, adds a produce action for each type this
        // unit produces.
        // a produce action is added for each free tile around the producer
        for (UnitType ut : type.produces) {
            if (p.getResources() >= ut.cost + base_ru.getResourcesUsed(player)) {
                int tup = (y > 0 ? pgs.getTerrain(x, y - 1) : PhysicalGameState.TERRAIN_WALL);
                int tright = (x < pgs.getWidth() - 1 ? pgs.getTerrain(x + 1, y) : PhysicalGameState.TERRAIN_WALL);
                int tdown = (y < pgs.getHeight() - 1 ? pgs.getTerrain(x, y + 1) : PhysicalGameState.TERRAIN_WALL);
                int tleft = (x > 0 ? pgs.getTerrain(x - 1, y) : PhysicalGameState.TERRAIN_WALL);

                if (tup == PhysicalGameState.TERRAIN_NONE && pgs.getUnitAt(x, y - 1) == null) {
                    var ua = new UnitAction(UnitAction.TYPE_PRODUCE, UnitAction.DIRECTION_UP, ut);
                    if (ua.resourceUsage(unit, pgs).consistentWith(base_ru, gs))
                        l.add(ua);
                }
                if (tright == PhysicalGameState.TERRAIN_NONE && pgs.getUnitAt(x + 1, y) == null) {
                    var ua = new UnitAction(UnitAction.TYPE_PRODUCE, UnitAction.DIRECTION_RIGHT, ut);
                    if (ua.resourceUsage(unit, pgs).consistentWith(base_ru, gs))
                        l.add(ua);
                }
                if (tdown == PhysicalGameState.TERRAIN_NONE && pgs.getUnitAt(x, y + 1) == null) {
                    var ua = new UnitAction(UnitAction.TYPE_PRODUCE, UnitAction.DIRECTION_DOWN, ut);
                    if (ua.resourceUsage(unit, pgs).consistentWith(base_ru, gs))
                        l.add(ua);
                }
                if (tleft == PhysicalGameState.TERRAIN_NONE && pgs.getUnitAt(x - 1, y) == null) {
                    var ua = new UnitAction(UnitAction.TYPE_PRODUCE, UnitAction.DIRECTION_LEFT, ut);
                    if (ua.resourceUsage(unit, pgs).consistentWith(base_ru, gs))
                        l.add(ua);
                }
            }
        }

        // if the unit can move, adds a move action for each free tile around it
        if (type.canMove) {
            int tup = (y > 0 ? pgs.getTerrain(x, y - 1) : PhysicalGameState.TERRAIN_WALL);
            int tright = (x < pgs.getWidth() - 1 ? pgs.getTerrain(x + 1, y) : PhysicalGameState.TERRAIN_WALL);
            int tdown = (y < pgs.getHeight() - 1 ? pgs.getTerrain(x, y + 1) : PhysicalGameState.TERRAIN_WALL);
            int tleft = (x > 0 ? pgs.getTerrain(x - 1, y) : PhysicalGameState.TERRAIN_WALL);

            if (tup == PhysicalGameState.TERRAIN_NONE && uup == null) {
                var ua = new UnitAction(UnitAction.TYPE_MOVE, UnitAction.DIRECTION_UP);
                if (ua.resourceUsage(unit, pgs).consistentWith(base_ru, gs))
                    l.add(ua);
            }
            if (tright == PhysicalGameState.TERRAIN_NONE && uright == null) {
                var ua = new UnitAction(UnitAction.TYPE_MOVE, UnitAction.DIRECTION_RIGHT);
                if (ua.resourceUsage(unit, pgs).consistentWith(base_ru, gs))
                    l.add(ua);
            }
            if (tdown == PhysicalGameState.TERRAIN_NONE && udown == null) {
                var ua = new UnitAction(UnitAction.TYPE_MOVE, UnitAction.DIRECTION_DOWN);
                if (ua.resourceUsage(unit, pgs).consistentWith(base_ru, gs))
                    l.add(ua);
            }
            if (tleft == PhysicalGameState.TERRAIN_NONE && uleft == null) {
                var ua = new UnitAction(UnitAction.TYPE_MOVE, UnitAction.DIRECTION_LEFT);
                if (ua.resourceUsage(unit, pgs).consistentWith(base_ru, gs))
                    l.add(ua);
            }

            if (unit != null) {
                Unit closestR = Closest(toPos(unit), _resources);

                if (closestR != null) {
                    UnitAction moveT = moveTowards(unit, toPos(closestR));

                    if (moveT != null && moveT.resourceUsage(unit, pgs).consistentWith(base_ru, gs)) {
                        l.add(moveT);
                    }
                }
            }
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

    Pos toPos(Unit u) {
        return new Pos(u.getX(), u.getY());
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

    Unit Closest(Pos src, List<Unit> units) {
        if (units.isEmpty())
            return null;
        Unit closest = units.stream().min(Comparator.comparing(u -> distance(src, toPos(u)))).get();
        return closest;
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
        //     return true;
        UnitActionAssignment aa = gs.getActionAssignment(u);
        return aa != null;
    }

    boolean dying(Unit u) {
        return u.getHitPoints() <= _newDmgs.getOrDefault(u, 0);
    }

    int combatScore(Unit u, Unit e, UnitTypeTable utt) {
        int score = -distance(u, e);

        if (u.getType() == utt.getUnitType("Ranged")
                && e.getType() == utt.getUnitType("Ranged") && _pgs.getWidth() > 9)
            score += 2; // todo may be change that and add logic below

        if (_pgs.getWidth() >= 16
                && (u.getType() == utt.getUnitType("Heavy") || u.getType() == utt.getUnitType("Ranged"))
                && (e.getType() == utt.getUnitType("Barracks"))) // todo - remove? todo base
            score += _pgs.getWidth();

        return score;
    }

    int[] getCombatScores(Unit u, List<Unit> targets, UnitTypeTable utt) {
        int[] scores = new int[targets.size()];
        int counter = 0;
        for (Unit t : targets) {
            scores[counter] = combatScore(u, t, utt);
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

    void goCombat(Unit u, UnitTypeTable utt) {
        if (busy(u) || !u.getType().canAttack) {
        }
        // continue;

        List<Unit> candidates = new ArrayList(_enemies);
        List<Unit> candidatesCopy = new ArrayList(candidates);
        int[] scores = getCombatScores(u, candidates, utt);
        Collections.sort(candidates, Comparator.comparing(e -> -scores[candidatesCopy.indexOf(e)])); // - for
                                                                                                     // ascending
                                                                                                     // order
        int counter = 0;
        int cutOff = _enemiesCombat.size() > 24 ? 12 : 24; // for performance
        // long timeRemain = timeRemaining(true);

        while (counter < candidates.size() && counter < cutOff) {
            Unit enemy = candidates.get(counter);
            // if (moveTowards(u, futurePoss(enemy)))
            // break;
            
            counter++;
        }
        if (counter < candidates.size()) // if (!candidates.isEmpty()) //did we make a move
            counter++;// continue;
        if (u.getType() != utt.getUnitType("Ranged")) {
        } // continue;
        Unit enemy = candidates.get(0);
        if (overPowering()) // give worker to open pathway if blocked
            tryMoveAway(u, u);
        // moveInDirection(u, enemy);
    }
}
