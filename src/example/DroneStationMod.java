package example;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.util.*;
import arc.struct.*;
import mindustry.content.*;
import mindustry.entities.*;
import mindustry.entities.bullet.*;
import mindustry.entities.pattern.*;
import mindustry.entities.abilities.*;
import mindustry.entities.units.*;
import mindustry.game.EventType.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.type.weapons.*;
import mindustry.world.blocks.units.*;
import mindustry.world.Tile;
import mindustry.mod.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.ai.types.*;
import mindustry.world.Block;
import static mindustry.Vars.*;

/**
 * Main class of the mod. Mindustry loads this class directly (see mod.hjson "main").
 */
public class DroneStationMod extends Mod {

    // ---------- unit types ----------
    public static UnitType orbitDrone, supportDrone, builderDrone, buffDrone;
    public static UnitType omegaOrbitDrone, omegaSupportDrone, omegaBuilderDrone, omegaBuffDrone;
    public static UnitType atraxOrbitDrone, atraxSupportDrone, atraxBuilderDrone, atraxBuffDrone;
    public static UnitType t4SupportDrone, t4MiniSupport, t5SupportDrone;
    public static DroneStation droneStation;

    // ---------- per-player / per-commander stats (refreshed once per frame) ----------
    public static IntIntMap t1Counts = new IntIntMap();
    public static IntIntMap t2Counts = new IntIntMap();
    public static IntIntMap t3Counts = new IntIntMap();
    public static IntIntMap tetherCounts = new IntIntMap();
    public static IntIntMap t4Counts = new IntIntMap();
    public static IntIntMap miniCounts = new IntIntMap();
    public static IntFloatMap playerShields = new IntFloatMap();

    // ---------- per-frame orbit cache ----------
    static IntIntMap droneCount = new IntIntMap();
    static IntIntMap droneIndex = new IntIntMap();
    static IntIntMap slotSum = new IntIntMap();
    static IntIntMap prefixSlots = new IntIntMap();
    static IntIntMap t4Sum = new IntIntMap();
    static IntIntMap prefixT4 = new IntIntMap();
    static IntIntMap firstAtraxBuff = new IntIntMap();

    // ---------- bullet buffs ----------
    public static IntIntMap buffedBullets = new IntIntMap();
    public static Seq<Bullet> activeBuffed = new Seq<>();
    public static StatusEffect[] buffEffects = new StatusEffect[10];
    public static StatusEffect atraxSpeedBuff;

    /** Mobile devices get fewer decorative draw calls. */
    public static boolean lowFx() {
        return mobile;
    }

    public static boolean isMyDrone(UnitType t) {
        return t == orbitDrone || t == supportDrone || t == builderDrone || t == buffDrone ||
               t == omegaOrbitDrone || t == omegaSupportDrone || t == omegaBuilderDrone || t == omegaBuffDrone ||
               t == atraxOrbitDrone || t == atraxSupportDrone || t == atraxBuilderDrone || t == atraxBuffDrone ||
               t == t4SupportDrone || t == t5SupportDrone;
    }

    public static int getDroneCost(UnitType t) {
        if (t == orbitDrone || t == supportDrone || t == builderDrone || t == buffDrone) return 2;
        if (t == omegaOrbitDrone || t == omegaSupportDrone || t == omegaBuilderDrone || t == omegaBuffDrone) return 3;
        if (t == atraxOrbitDrone || t == atraxSupportDrone || t == atraxBuilderDrone || t == atraxBuffDrone) return 6;
        if (t == t4SupportDrone) return 9;
        if (t == t5SupportDrone) return 18;
        return 0;
    }

    static void inc(IntIntMap map, int key) {
        map.put(key, map.get(key, 0) + 1);
    }

    /** Rebuilds all per-commander data in ONE pass over the unit group. */
    static void rebuildCache() {
        t1Counts.clear(); t2Counts.clear(); t3Counts.clear(); tetherCounts.clear();
        t4Counts.clear(); miniCounts.clear();
        droneCount.clear(); droneIndex.clear(); slotSum.clear(); prefixSlots.clear();
        t4Sum.clear(); prefixT4.clear(); firstAtraxBuff.clear();

        for (Unit u : Groups.unit) {
            Object ctrl = u.controller();

            if (ctrl instanceof MiniDroneAI) {
                MiniDroneAI mai = (MiniDroneAI) ctrl;
                if (mai.mother != null) inc(miniCounts, mai.mother.id);
                continue;
            }
            if (!(ctrl instanceof OrbitAI)) continue;
            OrbitAI ai = (OrbitAI) ctrl;
            if (ai.commander == null) continue;

            int c = ai.commander.id;

            if (isMyDrone(u.type)) {
                int used = slotSum.get(c, 0) + getDroneCost(u.type);
                slotSum.put(c, used);
                prefixSlots.put(u.id, used);

                droneIndex.put(u.id, droneCount.get(c, 0));
                inc(droneCount, c);

                if (u.type == t4SupportDrone) {
                    int n = t4Sum.get(c, 0) + 1;
                    t4Sum.put(c, n);
                    prefixT4.put(u.id, n);
                }
                if (u.type == atraxBuffDrone && !firstAtraxBuff.containsKey(c)) {
                    firstAtraxBuff.put(c, u.id);
                }
            }

            if (u.type == buffDrone) {
                inc(t1Counts, c);
            } else if (u.type == omegaBuffDrone) {
                inc(t2Counts, c);
            } else if (u.type == atraxBuffDrone) {
                inc(t3Counts, c);
                for (Ability a : u.abilities) {
                    if (a instanceof AtraxTetherAbility) {
                        AtraxTetherAbility ta = (AtraxTetherAbility) a;
                        if (ta.target != null) {
                            inc(tetherCounts, c);
                            break;
                        }
                    }
                }
            } else if (u.type == t4SupportDrone) {
                inc(t4Counts, c);
            }
        }
    }

    // =====================================================================
    // Abilities
    // =====================================================================

    public static class RoyalAuraAbility extends Ability {
        @Override
        public void update(Unit unit) {}

        @Override
        public void draw(Unit unit) {
            Draw.z(Layer.shields - 0.1f);
            Draw.color(Color.valueOf("73ff73"));
            float[] radiuses = lowFx() ? new float[]{ 65f } : new float[]{ 40f, 65f, 90f };
            for (int i = 0; i < radiuses.length; i++) {
                Lines.stroke(2f);
                Draw.alpha(0.5f + 0.2f * Mathf.absin(Time.time + i * 20f, 6f, 1f));
                Lines.circle(unit.x, unit.y, radiuses[i]);
            }
            Draw.reset();
        }
    }

    public static class BuffBeamAbility extends Ability {
        public Color color = Color.white;

        public BuffBeamAbility() {}

        public BuffBeamAbility(Color color) {
            this.color = color;
        }

        @Override
        public void update(Unit unit) {}

        @Override
        public void draw(Unit unit) {
            if (unit.controller() instanceof OrbitAI) {
                OrbitAI ai = (OrbitAI) unit.controller();
                if (ai.commander != null) {
                    Draw.z(Layer.bullet - 0.01f);
                    Lines.stroke(1.5f + Mathf.absin(Time.time, 4f, 0.5f), color);
                    Lines.line(unit.x, unit.y, ai.commander.x, ai.commander.y);
                    Draw.reset();
                }
            }
        }
    }

    public static class HealAuraAbility extends Ability {
        private float timer = 0f;

        @Override
        public void update(Unit unit) {
            timer += Time.delta;
            if (timer >= 120f) {
                timer = 0f;
                Fx.healWaveDynamic.at(unit.x, unit.y, 240f, Color.valueOf("73ff73"));
                Units.nearby(unit.team, unit.x, unit.y, 240f, other -> other.heal(50f));
                for (Building b : indexer.getDamaged(unit.team)) {
                    if (b != null && b.isValid() && unit.within(b, 240f)) {
                        b.heal(50f);
                        Fx.healBlockFull.at(b.x, b.y, b.block.size, Color.valueOf("73ff73"));
                    }
                }
            }
        }

        @Override
        public void draw(Unit unit) {}
    }

    public static class MiniBuildingHealAbility extends Ability {
        public float amount = 10f, reload = 60f, range = 40f;
        private float timer;

        public MiniBuildingHealAbility() {}

        public MiniBuildingHealAbility(float amount, float reload, float range) {
            this.amount = amount;
            this.reload = reload;
            this.range = range;
        }

        @Override
        public void update(Unit unit) {
            timer += Time.delta;
            if (timer >= reload) {
                timer = 0;
                indexer.eachBlock(unit.team, unit.x, unit.y, range, b -> b.damaged(), b -> {
                    b.heal(amount);
                    Fx.healBlock.at(b.x, b.y, b.block.size);
                });
            }
        }
    }

    public static class SpawnMiniAbility extends Ability {
        private float timer = 0f;
        private final float spawnTime = 180f;
        private static final int MAX_MINIS = 8;

        @Override
        public void update(Unit unit) {
            if (miniCounts.get(unit.id, 0) < MAX_MINIS) {
                timer += Time.delta;
                if (timer >= spawnTime) {
                    timer = 0f;
                    Unit mini = t4MiniSupport.create(unit.team);
                    mini.set(unit.x, unit.y);
                    mini.rotation = unit.rotation;
                    mini.controller(new MiniDroneAI(unit));
                    mini.add();
                    inc(miniCounts, unit.id);
                }
            } else {
                timer = 0f;
            }
        }

        @Override
        public void draw(Unit unit) {
            if (timer > 0f && miniCounts.get(unit.id, 0) < MAX_MINIS && t4MiniSupport != null && t4MiniSupport.region != null) {
                float progress = timer / spawnTime;
                Draw.z(Layer.flyingUnit + 0.1f);
                Draw.mixcol(Color.valueOf("73ff73"), 1f - progress);
                Draw.alpha(progress);
                Draw.rect(t4MiniSupport.region, unit.x, unit.y, unit.rotation - 90f);
                Draw.reset();
            }
        }
    }

    public static class AtraxTetherAbility extends Ability {
        public Teamc target;
        private float tick = 0f;

        @Override
        public void update(Unit unit) {
            tick += Time.delta;
            if (tick < 10f) return;
            tick = 0f;
            target = Units.closestTarget(unit.team, unit.x, unit.y, 240f, u -> !u.dead() && u.isAdded(), b -> b.isValid());
        }

        @Override
        public void draw(Unit unit) {
            if (target == null) return;
            if (unit.controller() instanceof OrbitAI) {
                OrbitAI ai = (OrbitAI) unit.controller();
                if (ai.commander != null && firstAtraxBuff.get(ai.commander.id, -1) == unit.id) {
                    final float currentShield = playerShields.get(ai.commander.id, 0f);
                    if (currentShield > 0f) {
                        int c3 = t3Counts.get(ai.commander.id, 0);
                        int tethers = tetherCounts.get(ai.commander.id, 0);
                        final float maxShield = tethers == 0 ? 1000f : tethers * 1000f;
                        final float radius = 48f + c3 * 10f;
                        final Unit cmd = ai.commander;

                        Draw.draw(Layer.shields, () -> {
                            float alpha = Math.min(currentShield / maxShield, 1f) * 0.5f;
                            if (!lowFx()) {
                                Draw.color(Color.valueOf("73d9ff"), Color.white, Mathf.absin(Time.time, 8f, 1f));
                                Draw.alpha(alpha);
                                Fill.poly(cmd.x, cmd.y, 6, radius);
                            }
                            Draw.alpha(alpha * 2f);
                            Lines.stroke(2f);
                            Draw.color(Color.valueOf("73d9ff"));
                            Lines.poly(cmd.x, cmd.y, 6, radius);
                            Draw.reset();
                        });
                    }
                }
            }
            Draw.z(Layer.bullet - 0.01f);
            Lines.stroke(2f + Mathf.absin(Time.time, 4f, 1f), Color.valueOf("73d9ff"));
            Lines.line(unit.x, unit.y, target.x(), target.y());
            Draw.reset();
        }
    }

    // =====================================================================
    // Content loading and world events
    // =====================================================================

    @Override
    public void loadContent() {
        atraxSpeedBuff = new StatusEffect("atrax-speed-buff") {{
            speedMultiplier = 1.25f;
            show = false;
        }};

        for (int i = 1; i <= 9; i++) {
            final int finalI = i;
            buffEffects[i] = new StatusEffect("drone-buff-" + i) {{
                damageMultiplier = 1f + (0.25f * finalI);
                reloadMultiplier = 1f + (0.25f * finalI);
                show = false;
            }};
        }

        Events.run(Trigger.update, () -> {
            if (state == null || !state.isGame()) return;
            rebuildCache();
            updatePlayers();
            updateBuffedBullets();
        });

        createUnits();
        createStation();

        Events.on(ContentInitEvent.class, e -> setupReconstructorsAndTech());
    }

    /** Shields, buffs and bullet absorption for each player. */
    static void updatePlayers() {
        for (Player p : Groups.player) {
            if (p.unit() == null) continue;
            final Unit pu = p.unit();
            int c1 = t1Counts.get(pu.id, 0);
            int c2 = t2Counts.get(pu.id, 0);
            int c3 = t3Counts.get(pu.id, 0);
            int tethers = tetherCounts.get(pu.id, 0);

            int totalBuffs = c1 + c2;
            if (totalBuffs > 0) {
                pu.apply(buffEffects[Math.min(totalBuffs, 9)], 15f);
            }
            if (c3 > 0) {
                pu.apply(atraxSpeedBuff, 15f);
            }

            float maxShield = tethers * 1000f;
            float currentShield = playerShields.get(pu.id, 0f);

            if (currentShield < maxShield) {
                currentShield = Math.min(maxShield, currentShield + tethers * 10f);
            } else if (currentShield > maxShield) {
                currentShield = Math.max(maxShield, currentShield - 10f);
            }
            if (tethers == 0 && currentShield > 0) {
                currentShield = Math.max(0f, currentShield - 15f);
            }
            playerShields.put(pu.id, currentShield);

            if (currentShield > 0f) {
                final float radius = 48f + c3 * 10f;
                final int pid = pu.id;
                final Team pteam = pu.team;
                Groups.bullet.intersect(pu.x - radius, pu.y - radius, radius * 2f, radius * 2f, b -> {
                    if (b.team != pteam && b.type != null && b.type.absorbable && Mathf.within(pu.x, pu.y, b.x, b.y, radius)) {
                        float current = playerShields.get(pid, 0f);
                        if (current <= 0f) return;
                        b.absorb();
                        Fx.absorb.at(b);
                        playerShields.put(pid, Math.max(0f, current - b.damage));
                    }
                });
            }
        }
    }

    /** Splash damage for bullets from buffed player shots when they despawn. */
    static void updateBuffedBullets() {
        for (int i = activeBuffed.size - 1; i >= 0; i--) {
            final Bullet b = activeBuffed.get(i);
            if (!b.isAdded()) {
                final int c2 = buffedBullets.get(b.id, 0);
                if (c2 > 0) {
                    final float splashRadius = 8f + (4f * c2);
                    float splashDmg = 5f * c2;
                    Damage.damage(b.team, b.x, b.y, splashRadius, splashDmg);
                    Fx.blastExplosion.at(b.x, b.y);
                    Units.nearbyEnemies(b.team, b.x - splashRadius, b.y - splashRadius, splashRadius * 2f, splashRadius * 2f, e -> {
                        if (e.within(b.x, b.y, splashRadius)) {
                            e.apply(StatusEffects.burning, 60f + (30f * c2));
                        }
                    });
                }
                buffedBullets.remove(b.id);
                activeBuffed.remove(i);
            }
        }

        for (Bullet b : Groups.bullet) {
            if (b.owner instanceof Unit) {
                Unit u = (Unit) b.owner;
                int c2 = t2Counts.get(u.id, 0);
                if (c2 > 0 && !buffedBullets.containsKey(b.id)) {
                    buffedBullets.put(b.id, c2);
                    activeBuffed.add(b);
                }
            }
        }
    }

    static void createUnits() {
        orbitDrone = new UnitType("orbit-drone") {{
            localizedName = "Orbit Drone";
            description = "Defensive drone that orbits the player.";
            flying = true; health = 200f; speed = 5f; drag = 0.06f; accel = 0.15f;
            hitSize = 9f; engineSize = 3f; isEnemy = false; useUnitCap = false;
            logicControllable = false; playerControllable = false;
            constructor = UnitEntity::create; controller = u -> new OrbitAI();

            weapons.add(new Weapon("") {{
                rotate = true; rotateSpeed = 15f; reload = 20f; shootSound = Sounds.shoot;
                bullet = new BasicBulletType(4f, 15f) {{ width = 8f; height = 11f; lifetime = 75f; }};
            }});
        }};

        supportDrone = new UnitType("support-drone") {{
            localizedName = "Support Drone";
            description = "Healing drone that orbits the player.";
            flying = true; health = 150f; speed = 5f; drag = 0.06f; accel = 0.15f;
            hitSize = 9f; engineSize = 3f; isEnemy = false; useUnitCap = false;
            logicControllable = false; playerControllable = false;
            constructor = UnitEntity::create; controller = u -> new OrbitAI();

            weapons.add(new RepairBeamWeapon("") {{
                rotate = true;
                x = 0f; y = 0f; shootY = 0f; beamWidth = 0.8f;
                repairSpeed = 0.5f; fractionRepairSpeed = 0.01f;
                bullet = new BulletType() {{ maxRange = 160f; }};
            }});
        }};

        builderDrone = new UnitType("builder-drone") {{
            localizedName = "Builder Drone";
            description = "Utility drone that helps the player build structures.";
            flying = true; health = 130f; speed = 5.5f; drag = 0.06f; accel = 0.15f;
            hitSize = 9f; engineSize = 3f; isEnemy = false; useUnitCap = false;
            logicControllable = false; playerControllable = false;
            buildSpeed = 0.3f;
            constructor = UnitEntity::create; controller = u -> new OrbitAI();
        }};

        buffDrone = new UnitType("buff-drone") {{
            localizedName = "Buff Drone";
            description = "Support drone that tethers to the player, increasing damage and fire rate by 25%. Can be stacked.";
            flying = true; health = 180f; speed = 5f; drag = 0.06f; accel = 0.15f;
            hitSize = 9f; engineSize = 3f; isEnemy = false; useUnitCap = false;
            logicControllable = false; playerControllable = false;
            constructor = UnitEntity::create; controller = u -> new OrbitAI();
            abilities.add(new BuffBeamAbility(Color.valueOf("ffaa55")));
        }};

        omegaOrbitDrone = new UnitType("omega-orbital-drone") {{
            localizedName = "Omega Orbital Drone";
            description = "Upgraded combat drone. Fires incendiary bursts.";
            flying = true; health = 450f; speed = 4.5f; drag = 0.06f; accel = 0.15f;
            hitSize = 11f; engineSize = 4f; isEnemy = false; useUnitCap = false;
            logicControllable = false; playerControllable = false;
            constructor = UnitEntity::create; controller = u -> new OrbitAI();

            weapons.add(new Weapon("") {{
                rotate = true; rotateSpeed = 15f; reload = 45f; shootSound = Sounds.shoot;
                shoot = new ShootPattern() {{ shots = 4; shotDelay = 4f; }};
                bullet = new BasicBulletType(5f, 22f) {{
                    width = 9f; height = 13f; lifetime = 75f;
                    status = StatusEffects.burning;
                }};
            }});
        }};

        omegaSupportDrone = new UnitType("omega-support-drone") {{
            localizedName = "Omega Support Drone";
            description = "Upgraded healing drone. Can heal 3 targets simultaneously.";
            flying = true; health = 350f; speed = 4.5f; drag = 0.06f; accel = 0.15f;
            hitSize = 11f; engineSize = 4f; isEnemy = false; useUnitCap = false;
            logicControllable = false; playerControllable = false;
            constructor = UnitEntity::create; controller = u -> new OrbitAI();

            for (int i = 0; i < 3; i++) {
                weapons.add(new RepairBeamWeapon("") {{
                    rotate = true;
                    x = 0f; y = 0f; shootY = 0f; beamWidth = 0.9f;
                    repairSpeed = 0.7f; fractionRepairSpeed = 0.015f;
                    bullet = new BulletType() {{ maxRange = 180f; }};
                }});
            }
        }};

        omegaBuilderDrone = new UnitType("omega-builder-drone") {{
            localizedName = "Omega Builder Drone";
            description = "Upgraded builder drone. Builds faster and has a protective hex shield.";
            flying = true; health = 300f; speed = 5.0f; drag = 0.06f; accel = 0.15f;
            hitSize = 11f; engineSize = 4f; isEnemy = false; useUnitCap = false;
            logicControllable = false; playerControllable = false;
            buildSpeed = 0.85f;
            abilities.add(new ForceFieldAbility(12f, 0.5f, 350f, 300f));
            constructor = UnitEntity::create; controller = u -> new OrbitAI();
        }};

        omegaBuffDrone = new UnitType("omega-buff-drone") {{
            localizedName = "Omega Buff Drone";
            description = "Upgraded buff drone. Increases damage and fire rate by 25%, and infuses player bullets with stacking explosive and incendiary properties.";
            flying = true; health = 380f; speed = 4.5f; drag = 0.06f; accel = 0.15f;
            hitSize = 11f; engineSize = 4f; isEnemy = false; useUnitCap = false;
            logicControllable = false; playerControllable = false;
            constructor = UnitEntity::create; controller = u -> new OrbitAI();
            abilities.add(new BuffBeamAbility(Color.valueOf("ff5555")));
        }};

        atraxOrbitDrone = new UnitType("atrax-orbital-drone") {{
            localizedName = "Atrax Orbital Drone";
            description = "Heavy combat drone. Fires explosive incendiary missiles.";
            flying = true; health = 950f; speed = 4.0f; drag = 0.06f; accel = 0.15f;
            hitSize = 14f; engineSize = 5f; isEnemy = false; useUnitCap = false;
            logicControllable = false; playerControllable = false;
            constructor = UnitEntity::create; controller = u -> new OrbitAI();

            weapons.add(new Weapon("atrax-missile") {{
                x = 5f; y = 0f;
                mirror = true; alternate = true;
                rotate = true; rotateSpeed = 10f; reload = 25f; shootSound = Sounds.shoot;
                shoot = new ShootPattern() {{ shots = 2; shotDelay = 4f; }};
                bullet = new MissileBulletType(5f, 50f) {{
                    width = 10f; height = 15f; lifetime = 60f;
                    splashDamage = 45f; splashDamageRadius = 32f;
                    status = StatusEffects.burning;
                    weaveScale = 4f; weaveMag = 3f;
                    trailColor = Color.valueOf("ffaa55"); trailWidth = 2.5f; trailLength = 12;
                    frontColor = Color.white; backColor = Color.orange;
                }};
            }});
        }};

        atraxSupportDrone = new UnitType("atrax-support-drone") {{
            localizedName = "Atrax Support Drone";
            description = "Advanced support drone. Saps health from enemies to power a massive healing beam.";
            flying = true; health = 800f; speed = 4.0f; drag = 0.06f; accel = 0.15f;
            hitSize = 14f; engineSize = 5f; isEnemy = false; useUnitCap = false;
            logicControllable = false; playerControllable = false;
            constructor = UnitEntity::create; controller = u -> new OrbitAI();

            weapons.add(new RepairBeamWeapon("") {{
                rotate = true;
                x = 0f; y = 0f; shootY = 0f; beamWidth = 1.5f;
                repairSpeed = 2.0f; fractionRepairSpeed = 0.03f;
                bullet = new BulletType() {{ maxRange = 260f; }};
            }});

            weapons.add(new Weapon("sap-weapon") {{
                shootSound = Sounds.shoot; x = 0; y = 0; reload = 15f; rotate = true;
                bullet = new SapBulletType() {{
                    sapStrength = 2.0f; length = 260f; damage = 40f; width = 1.2f;
                    color = Color.valueOf("73ff73"); hitColor = Color.valueOf("73ff73");
                }};
            }});
        }};

        atraxBuilderDrone = new UnitType("atrax-builder-drone") {{
            localizedName = "Atrax Builder Drone";
            description = "Master builder drone. Generates a massive shield and deploys temporary automated turrets in combat.";
            flying = true; health = 750f; speed = 4.5f; drag = 0.06f; accel = 0.15f;
            hitSize = 14f; engineSize = 5f; isEnemy = false; useUnitCap = false;
            logicControllable = false; playerControllable = false;
            buildSpeed = 1.5f;
            abilities.add(new ForceFieldAbility(24f, 1.5f, 1000f, 300f));
            constructor = UnitEntity::create; controller = u -> new OrbitAI();
        }};

        atraxBuffDrone = new UnitType("atrax-buff-drone") {{
            localizedName = "Atrax Buff Drone";
            description = "Advanced buff drone. Tethers to nearby enemies to generate a massive protective dome shield for the player. Increases player movement speed by 25%.";
            flying = true; health = 850f; speed = 4.0f; drag = 0.06f; accel = 0.15f;
            hitSize = 14f; engineSize = 5f; isEnemy = false; useUnitCap = false;
            logicControllable = false; playerControllable = false;
            constructor = UnitEntity::create; controller = u -> new OrbitAI();
            abilities.add(new AtraxTetherAbility());
        }};

        t4SupportDrone = new UnitType("t4-support-drone") {{
            localizedName = "Higher Medicine";
            description = "Generates mini support drones that independently heal units and structures. Max 2 per player.";
            flying = true; health = 1500f; speed = 3.5f; drag = 0.06f; accel = 0.15f;
            hitSize = 18f; engineSize = 6f; isEnemy = false; useUnitCap = false;
            logicControllable = false; playerControllable = false;
            constructor = UnitEntity::create; controller = u -> new OrbitAI();
            abilities.add(new HealAuraAbility());
            abilities.add(new SpawnMiniAbility());
        }};

        t4MiniSupport = new UnitType("t4-mini-support") {{
            localizedName = "Mini Support Drone";
            description = "A tiny drone deployed by Higher Medicine. Heals everything nearby.";
            canHeal = true;
            flying = true; health = 50f; speed = 4.5f; drag = 0.06f; accel = 0.15f;
            hitSize = 4f; engineSize = 1.5f; isEnemy = false; useUnitCap = false;
            logicControllable = false; playerControllable = false;
            constructor = UnitEntity::create; controller = u -> new MiniDroneAI(null);

            abilities.add(new MiniBuildingHealAbility(10f, 60f, 40f));

            weapons.add(new RepairBeamWeapon("") {{
                x = 0f; y = 0f; shootY = 0f; beamWidth = 0.4f;
                repairSpeed = 0.15f; fractionRepairSpeed = 0.005f;
                targetUnits = true;
                targetBuildings = true;
                bullet = new BulletType() {{ maxRange = 100f; }};
            }});
        }};

        t5SupportDrone = new UnitType("royal-medicine") {{
            localizedName = "Royal Medicine";
            description = "Ultimate support carrier drone. Summons an army of specialized healing drones and acts as a mobile hospital.";
            flying = true; health = 4500f; speed = 2.5f; drag = 0.06f; accel = 0.15f;
            hitSize = 26f; engineSize = 8f; isEnemy = false; useUnitCap = false;
            logicControllable = false; playerControllable = false;
            constructor = UnitEntity::create; controller = u -> new RoyalOrbitAI();
            abilities.add(new RoyalAuraAbility());
            abilities.add(new HealAuraAbility());
        }};
    }

    static void createStation() {
        droneStation = new DroneStation("drone-station") {{
            localizedName = "Drone Station";
            description = "Produces orbital drones.";
            requirements(Category.units, ItemStack.with(Items.copper, 75, Items.lead, 50, Items.silicon, 30));
            size = 2;
            consumePower(1.5f);
            plans.add(
                new UnitPlan(orbitDrone, 600f, ItemStack.with(Items.copper, 20, Items.silicon, 15)),
                new UnitPlan(supportDrone, 600f, ItemStack.with(Items.plastanium, 10, Items.metaglass, 20)),
                new UnitPlan(builderDrone, 600f, ItemStack.with(Items.titanium, 15, Items.silicon, 20)),
                new UnitPlan(buffDrone, 600f, ItemStack.with(Items.coal, 20, Items.pyratite, 15))
            );
        }};
    }

    static void addUpgrade(Block block, UnitType from, UnitType to) {
        if (block instanceof Reconstructor) {
            ((Reconstructor) block).upgrades.add(new UnitType[]{ from, to });
        }
    }

    static void setupReconstructorsAndTech() {
        addUpgrade(Blocks.additiveReconstructor, orbitDrone, omegaOrbitDrone);
        addUpgrade(Blocks.additiveReconstructor, supportDrone, omegaSupportDrone);
        addUpgrade(Blocks.additiveReconstructor, builderDrone, omegaBuilderDrone);
        addUpgrade(Blocks.additiveReconstructor, buffDrone, omegaBuffDrone);

        addUpgrade(Blocks.multiplicativeReconstructor, omegaOrbitDrone, atraxOrbitDrone);
        addUpgrade(Blocks.multiplicativeReconstructor, omegaSupportDrone, atraxSupportDrone);
        addUpgrade(Blocks.multiplicativeReconstructor, omegaBuilderDrone, atraxBuilderDrone);
        addUpgrade(Blocks.multiplicativeReconstructor, omegaBuffDrone, atraxBuffDrone);

        addUpgrade(Blocks.exponentialReconstructor, atraxSupportDrone, t4SupportDrone);
        addUpgrade(Blocks.tetrativeReconstructor, t4SupportDrone, t5SupportDrone);

        TechTree.TechNode parentNode = TechTree.all.find(node -> node.content == Blocks.groundFactory);
        if (parentNode == null) return;

        TechTree.TechNode stationNode = new TechTree.TechNode(parentNode, droneStation, ItemStack.with(Items.silicon, 2000, Items.copper, 4000));

        TechTree.TechNode t1Orbit = new TechTree.TechNode(stationNode, orbitDrone, ItemStack.with(Items.silicon, 500, Items.copper, 1000));
        TechTree.TechNode t2Orbit = new TechTree.TechNode(t1Orbit, omegaOrbitDrone, ItemStack.with(Items.silicon, 1500, Items.graphite, 1000));
        new TechTree.TechNode(t2Orbit, atraxOrbitDrone, ItemStack.with(Items.silicon, 3000, Items.surgeAlloy, 500));

        TechTree.TechNode t1Support = new TechTree.TechNode(stationNode, supportDrone, ItemStack.with(Items.silicon, 500, Items.plastanium, 500));
        TechTree.TechNode t2Support = new TechTree.TechNode(t1Support, omegaSupportDrone, ItemStack.with(Items.silicon, 1500, Items.plastanium, 1000));
        TechTree.TechNode t3Support = new TechTree.TechNode(t2Support, atraxSupportDrone, ItemStack.with(Items.silicon, 3000, Items.phaseFabric, 500));
        TechTree.TechNode t4SupportNode = new TechTree.TechNode(t3Support, t4SupportDrone, ItemStack.with(Items.silicon, 6000, Items.surgeAlloy, 1000));
        new TechTree.TechNode(t4SupportNode, t5SupportDrone, ItemStack.with(Items.silicon, 12000, Items.surgeAlloy, 3000, Items.phaseFabric, 2000));

        TechTree.TechNode t1Builder = new TechTree.TechNode(stationNode, builderDrone, ItemStack.with(Items.silicon, 500, Items.titanium, 500));
        TechTree.TechNode t2Builder = new TechTree.TechNode(t1Builder, omegaBuilderDrone, ItemStack.with(Items.silicon, 1500, Items.titanium, 1000));
        new TechTree.TechNode(t2Builder, atraxBuilderDrone, ItemStack.with(Items.silicon, 3000, Items.thorium, 1500));

        TechTree.TechNode t1Buff = new TechTree.TechNode(stationNode, buffDrone, ItemStack.with(Items.silicon, 500, Items.coal, 1000));
        TechTree.TechNode t2Buff = new TechTree.TechNode(t1Buff, omegaBuffDrone, ItemStack.with(Items.silicon, 1500, Items.pyratite, 500));
        new TechTree.TechNode(t2Buff, atraxBuffDrone, ItemStack.with(Items.silicon, 3000, Items.phaseFabric, 500));
    }

    // =====================================================================
    // AI controllers
    // =====================================================================

    /** Helper for controllers that need to fly toward a target and stop at a distance. */
    static void flyToward(Unit unit, Position target, float stopDist) {
        if (!unit.within(target, stopDist)) {
            unit.moveAt(new Vec2().trns(unit.angleTo(target), unit.speed()));
        } else {
            unit.vel.scl(0.9f);
        }
    }

    public static class MiniDroneAI extends AIController {
        public Unit mother;
        private final float angleOffset = Mathf.random(360f);
        private final Interval miniTimer = new Interval(1);
        private Position cachedTarget;

        public MiniDroneAI(Unit mother) {
            this.mother = mother;
        }

        private boolean isTargetGone() {
            if (cachedTarget == null) return true;
            if (cachedTarget instanceof Unit) {
                Unit cu = (Unit) cachedTarget;
                return cu.dead || !cu.isAdded();
            }
            if (cachedTarget instanceof Building) {
                return !((Building) cachedTarget).isValid();
            }
            return false;
        }

        @Override
        public void updateUnit() {
            if (mother == null || mother.dead || !mother.isAdded()) {
                unit.kill();
                return;
            }

            if (miniTimer.get(0, 20f) || isTargetGone()) {
                cachedTarget = findHealTarget();
            }

            if (cachedTarget != null) {
                flyToward(unit, cachedTarget, 60f);
                unit.aim(cachedTarget);
                unit.controlWeapons(true, true);
                return;
            }

            float orbitRadius = 25f;
            float speedRotation = 3f;
            float currentAngle = (Time.time * speedRotation) + angleOffset;
            float targetX = mother.x + Angles.trnsx(currentAngle, orbitRadius);
            float targetY = mother.y + Angles.trnsy(currentAngle, orbitRadius);

            if (unit.within(targetX, targetY, 2f)) {
                unit.vel.setZero();
            } else {
                unit.moveAt(new Vec2(targetX - unit.x, targetY - unit.y).limit(unit.speed()));
            }
            unit.controlWeapons(false, false);
            unit.rotation = Mathf.slerpDelta(unit.rotation, currentAngle + 90f, 0.1f);
        }

        private Position findHealTarget() {
            Unit closestUnit = null;
            float closestDist = Float.MAX_VALUE;
            for (Unit u : Groups.unit) {
                if (u != unit && u.team == unit.team && u.health < u.maxHealth) {
                    float d = unit.dst2(u);
                    if (d < closestDist) {
                        closestDist = d;
                        closestUnit = u;
                    }
                }
            }
            Building closestBuild = null;
            float closestBuildDist = Float.MAX_VALUE;
            for (Building b : indexer.getDamaged(unit.team)) {
                if (b == null || !b.isValid()) continue;
                float d = unit.dst2(b);
                if (d < closestBuildDist) {
                    closestBuildDist = d;
                    closestBuild = b;
                }
            }
            if (closestUnit != null && (closestBuild == null || closestDist < closestBuildDist)) return closestUnit;
            return closestBuild;
        }
    }

    public static class ChildDroneAI extends AIController {
        public Unit mother;
        public RoyalOrbitAI motherAI;
        public float angleOffset = Mathf.random(360f);
        public int ringIndex;

        public Unit targetUnit = null;
        public Building targetBuild = null;

        private float alertCooldown = 0f;

        public ChildDroneAI(Unit mother, RoyalOrbitAI ai) {
            this.mother = mother;
            this.motherAI = ai;
            this.ringIndex = Mathf.random(0, 2);
        }

        @Override
        public void updateUnit() {
            if (mother == null || mother.dead || !mother.isAdded()) {
                unit.kill();
                return;
            }

            if (alertCooldown > 0f) alertCooldown -= Time.delta;
            if (alertCooldown <= 0f) {
                Unit enemy = Units.closestEnemy(unit.team, unit.x, unit.y, 250f, e -> !e.dead());
                if (enemy != null && motherAI != null) {
                    motherAI.triggerEmergencyT3(enemy.x, enemy.y);
                }
                alertCooldown = 60f;
            }

            boolean hasTask = false;

            if (targetUnit != null) {
                if (targetUnit.dead || !targetUnit.isAdded() || targetUnit.health >= targetUnit.maxHealth) {
                    targetUnit = null;
                } else {
                    hasTask = true;
                    flyToward(unit, targetUnit, 60f);
                    unit.rotation = Mathf.slerpDelta(unit.rotation, unit.angleTo(targetUnit), 0.1f);
                    unit.aim(targetUnit);
                    unit.controlWeapons(true, true);
                }
            } else if (targetBuild != null) {
                if (!targetBuild.isValid() || targetBuild.health >= targetBuild.maxHealth) {
                    targetBuild = null;
                } else {
                    hasTask = true;
                    flyToward(unit, targetBuild, 60f);
                    unit.rotation = Mathf.slerpDelta(unit.rotation, unit.angleTo(targetBuild), 0.1f);
                    unit.aim(targetBuild);
                    unit.controlWeapons(true, true);
                }
            }

            if (!hasTask) {
                float[] radiuses = { 40f, 65f, 90f };
                float radius = radiuses[ringIndex % 3];
                float speedRotation = 2f - (ringIndex * 0.5f);
                float currentAngle = (Time.time * speedRotation) + angleOffset;
                float targetX = mother.x + Angles.trnsx(currentAngle, radius);
                float targetY = mother.y + Angles.trnsy(currentAngle, radius);

                if (unit.within(targetX, targetY, 3f)) {
                    unit.vel.scl(0.8f);
                } else {
                    unit.moveAt(new Vec2(targetX - unit.x, targetY - unit.y).limit(unit.speed()));
                }
                unit.controlWeapons(false, false);
                unit.rotation = Mathf.slerpDelta(unit.rotation, currentAngle + 90f, 0.1f);
            }
        }
    }

    public static class RoyalOrbitAI extends OrbitAI {
        public boolean initialized = false;
        public float miniTimer = 0f;
        public float t1Timer = 0f;
        public float t3Cooldown = 0f;
        public Unit t3Helper = null;
        public float lastHealth = -1;
        public float dispatchTimer = 0f;

        public Seq<Unit> children = new Seq<>();

        @Override
        public void updateUnit() {
            super.updateUnit();

            if (commander == null) return;

            if (!initialized) {
                for (int i = 0; i < 5; i++) spawnChild(t4MiniSupport);
                for (int i = 0; i < 3; i++) spawnChild(supportDrone);
                for (int i = 0; i < 2; i++) spawnChild(omegaSupportDrone);
                initialized = true;
                lastHealth = unit.health;
            }

            children.removeAll(u -> u == null || u.dead || !u.isAdded());

            int miniCount = children.count(u -> u.type == t4MiniSupport);
            int t1Count = children.count(u -> u.type == supportDrone);

            miniTimer += Time.delta;
            if (miniTimer >= 300f && miniCount < 16) {
                miniTimer = 0f;
                spawnChild(t4MiniSupport);
                spawnChild(t4MiniSupport);
            }

            t1Timer += Time.delta;
            if (t1Timer >= 600f && t1Count < 5) {
                t1Timer = 0f;
                spawnChild(supportDrone);
            }

            if (t3Cooldown > 0) t3Cooldown -= Time.delta;

            if (unit.health < lastHealth - 5f) {
                triggerEmergencyT3(unit.x, unit.y);
            }
            lastHealth = unit.health;

            dispatchTimer += Time.delta;
            if (dispatchTimer >= 60f) {
                dispatchTimer = 0f;
                manageChildren();
            }
        }

        private void spawnChild(UnitType type) {
            Unit child = type.create(unit.team);
            if (child != null) {
                child.set(unit.x, unit.y);
                child.rotation = unit.rotation;
                child.controller(new ChildDroneAI(unit, this));
                child.add();
                children.add(child);
            }
        }

        public void triggerEmergencyT3(float x, float y) {
            if (t3Cooldown > 0) return;
            if (t3Helper != null && !t3Helper.dead && t3Helper.isAdded()) return;

            t3Helper = atraxSupportDrone.create(unit.team);
            if (t3Helper != null) {
                t3Helper.set(unit.x, unit.y);
                t3Helper.rotation = unit.rotation;
                t3Helper.controller(new T3HelperAI(unit, x, y));
                t3Helper.add();
                t3Cooldown = 600f;
            }
        }

        private void manageChildren() {
            Position target = findDamagedTarget();
            if (target == null) return;
            int toDispatch = Mathf.random(2, 4);
            int dispatched = 0;
            for (Unit child : children) {
                if (child.controller() instanceof ChildDroneAI) {
                    ChildDroneAI cai = (ChildDroneAI) child.controller();
                    if (cai.targetUnit == null && cai.targetBuild == null) {
                        if (target instanceof Unit) cai.targetUnit = (Unit) target;
                        if (target instanceof Building) cai.targetBuild = (Building) target;
                        dispatched++;
                        if (dispatched >= toDispatch) break;
                    }
                }
            }
        }

        private Position findDamagedTarget() {
            for (Unit u : Groups.unit) {
                if (u.team == unit.team && u.health < u.maxHealth && u != unit && !children.contains(u) && Mathf.chance(0.5)) return u;
            }
            for (Building b : indexer.getDamaged(unit.team)) {
                if (b != null && b.isValid() && Mathf.chance(0.5)) return b;
            }
            for (Building b : indexer.getDamaged(unit.team)) {
                if (b != null && b.isValid()) return b;
            }
            for (Unit u : Groups.unit) {
                if (u.team == unit.team && u.health < u.maxHealth && u != unit && !children.contains(u)) return u;
            }
            return null;
        }
    }

    public static class OrbitAI extends AIController {
        public Unit commander;
        public float angleOffset;
        protected final Vec2 moveVec = new Vec2();
        public Interval orbitTimer = new Interval(2);

        protected Unit cachedEnemy;
        protected float retargetTimer = 0f;

        @Override
        public void updateUnit() {
            if (commander != null && (commander.dead || !commander.isAdded() || !commander.isPlayer())) {
                unit.kill();
                return;
            }

            if (commander != null && !slotsValid()) {
                commander = null;
            }

            if (commander == null) {
                commander = findCommander();
            }

            if (commander == null) {
                unit.vel.scl(0.95f);
                return;
            }

            int total = droneCount.get(commander.id, 0);
            int idx = droneIndex.get(unit.id, 0);
            angleOffset = total > 0 ? idx * (360f / total) : 0f;

            float speedRotation = 1.5f;
            float orbitRadius = 45f;
            float currentAngle = (Time.time * speedRotation) + angleOffset;
            float forwardAngle = currentAngle + 90f;

            float targetX = commander.x + Angles.trnsx(currentAngle, orbitRadius);
            float targetY = commander.y + Angles.trnsy(currentAngle, orbitRadius);

            moveVec.set(targetX - unit.x, targetY - unit.y);
            if (unit.within(targetX, targetY, 2f)) {
                unit.vel.setZero();
            } else {
                unit.moveAt(moveVec.limit(unit.speed()));
            }

            if (unit.type == t4SupportDrone || unit.type == t5SupportDrone) {
                unit.rotation = Mathf.slerpDelta(unit.rotation, forwardAngle, 0.1f);
                unit.controlWeapons(false, false);
            } else if (unit.type == supportDrone || unit.type == omegaSupportDrone) {
                unit.rotation = Mathf.slerpDelta(unit.rotation, forwardAngle, 0.1f);
                unit.aim(commander);
                boolean hurt = commander.health < commander.maxHealth;
                unit.controlWeapons(hurt, hurt);
            } else if (unit.type == atraxSupportDrone) {
                unit.rotation = Mathf.slerpDelta(unit.rotation, forwardAngle, 0.1f);
                Unit target = findEnemy(260f);
                if (target != null) {
                    unit.aim(target);
                    unit.controlWeapons(true, true);
                } else {
                    unit.aim(commander);
                    boolean hurt = commander.health < commander.maxHealth;
                    unit.controlWeapons(hurt, hurt);
                }
            } else if (unit.type == buffDrone || unit.type == omegaBuffDrone || unit.type == atraxBuffDrone) {
                unit.rotation = Mathf.slerpDelta(unit.rotation, forwardAngle, 0.1f);
                unit.controlWeapons(false, false);
            } else if (unit.type == builderDrone || unit.type == omegaBuilderDrone || unit.type == atraxBuilderDrone) {
                unit.rotation = Mathf.slerpDelta(unit.rotation, forwardAngle, 0.1f);

                if (unit.type == atraxBuilderDrone) {
                    updateTurretSpawn();
                }

                BuildPlan plan = commander.buildPlan();
                if (plan != null && plan.block != null) {
                    unit.aim(plan.x * 8f, plan.y * 8f);
                    unit.plans.clear();
                    // pass a COPY so the drone cannot mutate the player's build queue
                    unit.plans.addLast(new BuildPlan(plan.x, plan.y, plan.rotation, plan.block, plan.config));
                } else {
                    unit.plans.clear();
                }
                unit.controlWeapons(false, false);
            } else {
                unit.rotation = Mathf.slerpDelta(unit.rotation, forwardAngle, 0.1f);
                Unit target = findEnemy(300f);
                if (target != null) {
                    unit.aim(target);
                    unit.controlWeapons(true, true);
                } else {
                    unit.controlWeapons(false, false);
                }
            }
        }

        /** Atrax builder: drops a temporary hail turret next to itself when enemies are near. */
        private void updateTurretSpawn() {
            Unit enemy = findEnemy(300f);
            if (enemy == null || !orbitTimer.get(0, 300f)) return;

            Tile tile = world.tileWorld(unit.x, unit.y);
            if (tile == null || tile.block() != Blocks.air || tile.build != null) return;

            tile.setNet(Blocks.hail, unit.team, 0);
            if (tile.build == null) return;

            tile.build.health = tile.build.maxHealth;
            for (int i = 0; i < 40; i++) {
                tile.build.handleItem(tile.build, Items.graphite);
            }

            final Tile targetTile = tile;
            final Team targetTeam = unit.team;
            Time.run(1200f, () -> {
                if (targetTile.block() == Blocks.hail && targetTile.build != null && targetTile.build.team == targetTeam) {
                    targetTile.setNet(Blocks.air);
                    Fx.breakBlock.at(targetTile.worldx(), targetTile.worldy());
                }
            });
        }

        private boolean slotsValid() {
            if (prefixSlots.get(unit.id, 0) > 24) return false;
            return !(unit.type == t4SupportDrone && prefixT4.get(unit.id, 0) > 2);
        }

        private Unit findCommander() {
            for (Player player : Groups.player) {
                if (player.team() == unit.team && player.unit() != null) {
                    if (slotSum.get(player.unit().id, 0) + getDroneCost(unit.type) <= 18) {
                        return player.unit();
                    }
                }
            }
            return null;
        }

        protected Unit findEnemy(float range) {
            if (retargetTimer > 0f) retargetTimer -= Time.delta;
            boolean stale = cachedEnemy == null || !cachedEnemy.isAdded() || cachedEnemy.dead
                    || !unit.within(cachedEnemy, range);
            if (retargetTimer <= 0f || stale) {
                retargetTimer = 15f;
                cachedEnemy = Units.closestEnemy(unit.team, unit.x, unit.y, range, u -> !u.dead);
            }
            return cachedEnemy;
        }
    }

    public static class T3HelperAI extends AIController {
        public Unit mother;
        public Vec2 targetPos;
        public int state = 0;
        public float timer = 0f;

        public T3HelperAI(Unit mother, float tx, float ty) {
            this.mother = mother;
            this.targetPos = new Vec2(tx, ty);
        }

        @Override
        public void updateUnit() {
            if (mother == null || mother.dead || !mother.isAdded()) {
                unit.kill();
                return;
            }

            timer += Time.delta;

            if (state == 0) {
                if (timer > 300f) {
                    state = 1;
                } else {
                    flyToward(unit, targetPos, 100f);
                    unit.rotation = Mathf.slerpDelta(unit.rotation, unit.angleTo(targetPos), 0.1f);

                    Unit enemy = Units.closestEnemy(unit.team, unit.x, unit.y, 260f, e -> !e.dead());
                    if (enemy != null) {
                        unit.rotation = Mathf.slerpDelta(unit.rotation, unit.angleTo(enemy), 0.1f);
                        unit.aim(enemy);
                        unit.controlWeapons(true, true);
                    } else {
                        Unit ally = Units.closest(unit.team, unit.x, unit.y, 260f, u -> u.health < u.maxHealth);
                        if (ally != null) {
                            unit.rotation = Mathf.slerpDelta(unit.rotation, unit.angleTo(ally), 0.1f);
                            unit.aim(ally);
                            unit.controlWeapons(true, true);
                        } else {
                            unit.controlWeapons(false, false);
                        }
                    }
                }
            } else {
                unit.controlWeapons(false, false);
                if (!unit.within(mother, 20f)) {
                    unit.moveAt(new Vec2().trns(unit.angleTo(mother), unit.speed()));
                    unit.rotation = Mathf.slerpDelta(unit.rotation, unit.angleTo(mother), 0.1f);
                    unit.aim(mother);
                } else {
                    Fx.itemTransfer.at(unit.x, unit.y, 0, Color.valueOf("73ff73"), mother);
                    unit.remove();
                }
            }
        }
    }

    // =====================================================================
    // Station
    // =====================================================================

    public static class DroneStation extends UnitFactory {
        public DroneStation(String name) {
            super(name);
            timers = 2;
            buildType = DroneStationBuild::new;
        }

        @Override
        public void load() {
            super.load();
            region = Core.atlas.find(name, Core.atlas.find("clear"));
            outRegion = Core.atlas.find("clear");
        }

        public class DroneStationBuild extends UnitFactoryBuild {
            public boolean canProduce = false;

            @Override
            public void updateTile() {
                if (timer.get(1, 30f)) {
                    canProduce = false;
                    for (Player p : Groups.player) {
                        if (p.team() == team && p.unit() != null) {
                            if (slotSum.get(p.unit().id, 0) + 3 <= 24) {
                                canProduce = true;
                                break;
                            }
                        }
                    }
                }

                if (!canProduce) return;
                super.updateTile();
            }

            @Override
            public boolean shouldConsume() {
                return super.shouldConsume() && canProduce;
            }
        }
    }
}