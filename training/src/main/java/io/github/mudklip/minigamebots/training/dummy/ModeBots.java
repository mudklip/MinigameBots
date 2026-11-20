package io.github.mudklip.minigamebots.training.dummy;

import java.util.ArrayDeque;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Game-mode bots: opponents that actually play the minigame, the way a decent player (or a
 * Baritone-style bot) would, instead of just walking up and punching. They pathfind over the
 * floor that is left (never onto a hole or a crumbling block, jumping one-block gaps), and each
 * game has its own plan:
 * <ul>
 * <li>Spleef: keeps a few blocks away on safe ground and digs out the snow under and ahead of
 * its opponent.</li>
 * <li>TNT Run: never stops moving, always heading for the biggest patch of floor still there.</li>
 * <li>Sumo: stays off its own edge, gets between the opponent and the middle and sprint-hits them
 * outwards.</li>
 * <li>King of the Hill: takes the gold and knocks anyone near it off.</li>
 * <li>Snowball fight: keeps its distance, strafes, dodges incoming snowballs and throws with lead.</li>
 * </ul>
 * Difficulty follows the dummy's level (1-5): reaction, dig/throw rate and aim.
 * Server-side only: Baritone itself can only drive a player's own client, never these opponents.
 */
public final class ModeBots {
	/**
	 * The game field: floor at centre.y - 1, from -half to +half around the centre. With
	 * {@code ffa} (free-for-all, only when asked for) the bots also go after each other: each takes
	 * on whoever is nearest among {@code players}; otherwise they all play against you.
	 */
	public record Field(BlockPos centre, int half, String game, Predicate<BlockPos> crumbling, boolean ffa,
			java.util.function.Supplier<java.util.List<LivingEntity>> players) {
		public Field(BlockPos centre, int half, String game, Predicate<BlockPos> crumbling) {
			this(centre, half, game, crumbling, false, java.util.List::of);
		}
	}

	private ModeBots() {
	}

	/** A brain for this game, or null when the normal dummy fighting fits (One Hit). */
	public static DummyManager.@Nullable Brain of(Field f) {
		return switch (f.game()) {
			case "spleef" -> new Bot(f, Bot.SPLEEF);
			case "tnt_run" -> new Bot(f, Bot.TNT_RUN);
			case "sumo" -> new Bot(f, Bot.SUMO);
			case "king" -> new Bot(f, Bot.KING);
			case "snowball" -> new Bot(f, Bot.SNOWBALL);
			default -> null;
		};
	}

	private static final class Bot implements DummyManager.Brain {
		static final int SPLEEF = 0;
		static final int TNT_RUN = 1;
		static final int SUMO = 2;
		static final int KING = 3;
		static final int SNOWBALL = 4;

		final Field f;
		final int game;
		boolean equipped;
		int act;
		int think;
		int strafe = 1;
		int strafeFor;
		@Nullable BlockPos goal;
		@Nullable BlockPos next;
		final java.util.Map<BlockPos, Integer> visited = new java.util.HashMap<>();
		int age;

		Bot(Field f, int game) {
			this.f = f;
			this.game = game;
		}

		@Override
		public boolean tick(Mannequin m, DummyManager.Dummy d, @Nullable LivingEntity target, ServerLevel level) {
			age++;
			int lv = Mth.clamp(d.settings.level, 1, 5);
			if (f.ffa()) {
				LivingEntity best = null;
				for (LivingEntity e : f.players().get()) {
					if (e != m && e.isAlive() && !(e instanceof net.minecraft.world.entity.player.Player p && p.isSpectator())
							&& (best == null || e.distanceToSqr(m) < best.distanceToSqr(m))) {
						best = e;
					}
				}
				target = best;
			}
			if (!equipped) {
				equipped = true;
				m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(switch (game) {
					case SPLEEF -> Items.DIAMOND_SHOVEL;
					case SNOWBALL -> Items.SNOWBALL;
					case TNT_RUN -> Items.AIR;
					default -> Items.STICK; // Knockback games: the hit is all that matters.
				}));
				m.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
			}
			if (act > 0) {
				act--;
			}
			switch (game) {
				case SPLEEF -> spleef(m, target, level, lv);
				case TNT_RUN -> tntRun(m, level, lv);
				case SUMO -> sumo(m, d, target, level, lv);
				case KING -> king(m, d, target, level, lv);
				case SNOWBALL -> snowball(m, target, level, lv);
				default -> {
				}
			}
			return true;
		}

		// --- floor and paths ---

		int floorY() {
			return f.centre().getY() - 1;
		}

		boolean inside(int x, int z) {
			return Math.abs(x - f.centre().getX()) <= f.half() && Math.abs(z - f.centre().getZ()) <= f.half();
		}

		boolean solid(ServerLevel level, int x, int z) {
			return inside(x, z) && !level.getBlockState(new BlockPos(x, floorY(), z)).isAir();
		}

		/** Safe to stand on: there, and not about to crumble. */
		boolean safe(ServerLevel level, int x, int z) {
			return solid(level, x, z) && !f.crumbling().test(new BlockPos(x, floorY(), z));
		}

		/** How much safe floor is around (x, z) within r: the "don't stand next to a hole" score. */
		int room(ServerLevel level, int x, int z, int r) {
			int n = 0;
			for (int i = -r; i <= r; i++) {
				for (int j = -r; j <= r; j++) {
					if (safe(level, x + i, z + j)) {
						n++;
					}
				}
			}
			return n;
		}

		static BlockPos cell(Vec3 p) {
			return new BlockPos(Mth.floor(p.x), 0, Mth.floor(p.z));
		}

		/**
		 * Breadth-first path over safe floor (8 directions, no corner cutting past holes, and
		 * straight 2-block jumps over one-block gaps). Returns the first step, or null.
		 */
		@Nullable BlockPos firstStep(ServerLevel level, BlockPos from, BlockPos to) {
			if (from.equals(to)) {
				return to;
			}
			java.util.Map<BlockPos, BlockPos> came = new java.util.HashMap<>();
			ArrayDeque<BlockPos> q = new ArrayDeque<>();
			q.add(from);
			came.put(from, from);
			int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}};
			while (!q.isEmpty() && came.size() < 600) {
				BlockPos c = q.poll();
				if (c.equals(to)) {
					BlockPos s = c;
					while (!came.get(s).equals(from)) {
						s = came.get(s);
					}
					return s;
				}
				for (int[] dd : dirs) {
					int nx = c.getX() + dd[0];
					int nz = c.getZ() + dd[1];
					BlockPos n = new BlockPos(nx, 0, nz);
					if (came.containsKey(n) || !safe(level, nx, nz)) {
						continue;
					}
					if (Math.abs(dd[0]) + Math.abs(dd[1]) == 2 && dd[0] != 0 && dd[1] != 0
							&& (!safe(level, c.getX() + dd[0], c.getZ()) || !safe(level, c.getX(), c.getZ() + dd[1]))) {
						continue; // No cutting a corner past a hole.
					}
					if (Math.abs(dd[0]) == 2 || Math.abs(dd[1]) == 2) {
						int mx = c.getX() + dd[0] / 2;
						int mz = c.getZ() + dd[1] / 2;
						if (safe(level, mx, mz)) {
							continue; // Not a gap: walk it normally.
						}
					}
					came.put(n, c);
					q.add(n);
				}
			}
			return null;
		}

		/** Walks (or sprints) toward a cell along the path; jumps a gap when the next block is missing. */
		void go(Mannequin m, ServerLevel level, BlockPos to, double speed, boolean faceWay) {
			Vec3 pos = m.position();
			BlockPos here = cell(pos);
			if (think <= 0 || next == null || here.equals(next) || !to.equals(goal)) {
				goal = to;
				next = firstStep(level, here, to);
			}
			BlockPos step = next != null ? next : to;
			Vec3 aim = new Vec3(step.getX() + 0.5, pos.y, step.getZ() + 0.5);
			Vec3 dir = aim.subtract(pos);
			dir = new Vec3(dir.x, 0, dir.z);
			if (dir.lengthSqr() < 0.01) {
				return;
			}
			dir = dir.normalize();
			if (m.hurtTime == 0) {
				double y = m.getDeltaMovement().y;
				// A hole right in front: jump it (the path only goes this way when the far side is there).
				Vec3 ahead = pos.add(dir.scale(0.7));
				if (m.onGround() && !solid(level, Mth.floor(ahead.x), Mth.floor(ahead.z))) {
					y = 0.42;
					speed = Math.max(speed, 0.3);
				}
				m.setDeltaMovement(dir.x * speed, y, dir.z * speed);
				m.setSprinting(speed > 0.22);
			}
			if (faceWay) {
				float yaw = (float) (Mth.atan2(dir.z, dir.x) * Mth.RAD_TO_DEG) - 90f;
				m.setYRot(yaw);
				m.setYHeadRot(yaw);
				m.setYBodyRot(yaw);
				m.setXRot(10);
			}
		}

		void stop(Mannequin m) {
			if (m.hurtTime == 0) {
				m.setDeltaMovement(0, m.getDeltaMovement().y, 0);
				m.setSprinting(false);
			}
		}

		static void look(Mannequin m, Vec3 at) {
			Vec3 d = at.subtract(m.getEyePosition());
			float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
			float pitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
			m.setYRot(yaw);
			m.setYHeadRot(yaw);
			m.setYBodyRot(yaw);
			m.setXRot(pitch);
		}

		/** The best safe cell near a wanted spot: close to it, with plenty of floor around. */
		BlockPos bestNear(ServerLevel level, Vec3 want, int radius) {
			BlockPos c = cell(want);
			BlockPos best = null;
			double score = -1e9;
			for (int i = -radius; i <= radius; i++) {
				for (int j = -radius; j <= radius; j++) {
					int x = c.getX() + i;
					int z = c.getZ() + j;
					if (!safe(level, x, z)) {
						continue;
					}
					double s = room(level, x, z, 1) * 1.0 - Math.hypot(x + 0.5 - want.x, z + 0.5 - want.z) * 1.5;
					if (s > score) {
						score = s;
						best = new BlockPos(x, 0, z);
					}
				}
			}
			return best != null ? best : cell(new Vec3(f.centre().getX() + 0.5, 0, f.centre().getZ() + 0.5));
		}

		// --- the games ---

		void spleef(Mannequin m, @Nullable LivingEntity t, ServerLevel level, int lv) {
			think--;
			if (t == null) {
				stop(m);
				return;
			}
			Vec3 me = m.position();
			Vec3 tp = t.position();
			Vec3 away = new Vec3(me.x - tp.x, 0, me.z - tp.z);
			if (away.lengthSqr() < 1e-3) {
				away = new Vec3(1, 0, 0);
			}
			// Stand about 3 blocks from them, on solid ground; get away from holes first.
			Vec3 want = room(level, Mth.floor(me.x), Mth.floor(me.z), 1) < 6 ? me : tp.add(away.normalize().scale(3));
			if (think <= 0) {
				goal = bestNear(level, want, 3);
				think = 8 - lv;
			}
			BlockPos g = goal;
			if (g != null && cell(me).distManhattan(g) > 0) {
				go(m, level, g, 0.2, false);
			} else {
				stop(m);
			}
			look(m, tp);
			// Dig: the block under them first, then where they're heading, then next to them.
			// (Not in the first 3 seconds of a round, like the grace period on servers.)
			if (act == 0 && age > 60) {
				Vec3 v = t.getDeltaMovement();
				Vec3[] picks = {tp, tp.add(v.x * 6, 0, v.z * 6), tp.add(1, 0, 0), tp.add(-1, 0, 0), tp.add(0, 0, 1), tp.add(0, 0, -1)};
				for (Vec3 p : picks) {
					BlockPos b = new BlockPos(Mth.floor(p.x), floorY(), Mth.floor(p.z));
					if (!level.getBlockState(b).is(Blocks.SNOW_BLOCK)) {
						continue;
					}
					if (m.getEyePosition().distanceTo(Vec3.atCenterOf(b)) > 4.5) {
						continue;
					}
					if (m.getRandom().nextDouble() > 0.55 + lv * 0.09) {
						act = 4; // Missed the click.
						break;
					}
					look(m, Vec3.atCenterOf(b));
					m.swing(InteractionHand.MAIN_HAND, m.getMainHandItem().getAttackAnimation(), false);
					level.destroyBlock(b, false, m);
					act = 9 - lv; // L5 about 5 blocks a second, L1 about 2.5.
					break;
				}
			}
		}

		void tntRun(Mannequin m, ServerLevel level, int lv) {
			think--;
			BlockPos here = cell(m.position());
			visited.put(here, age);
			if (think <= 0 || goal == null || here.equals(goal) || !safe(level, goal.getX(), goal.getZ())) {
				// The biggest patch of floor still there, not where it has just been.
				BlockPos best = null;
				double score = -1e9;
				for (int i = -5; i <= 5; i++) {
					for (int j = -5; j <= 5; j++) {
						int x = here.getX() + i;
						int z = here.getZ() + j;
						if ((i == 0 && j == 0) || !safe(level, x, z)) {
							continue;
						}
						Integer when = visited.get(new BlockPos(x, 0, z));
						double s = room(level, x, z, 2) - Math.hypot(i, j) * 0.3 - (when != null && age - when < 60 ? 8 : 0)
								+ m.getRandom().nextDouble();
						if (s > score) {
							score = s;
							best = new BlockPos(x, 0, z);
						}
					}
				}
				goal = best;
				think = 12 - lv;
			}
			if (goal != null) {
				go(m, level, goal, lv >= 3 ? 0.28 : 0.24, true);
			}
		}

		double platformRadius() {
			return f.half() + 0.5;
		}

		void sumo(Mannequin m, DummyManager.Dummy d, @Nullable LivingEntity t, ServerLevel level, int lv) {
			knockGame(m, d, t, level, lv, new Vec3(f.centre().getX() + 0.5, 0, f.centre().getZ() + 0.5), platformRadius());
		}

		void king(Mannequin m, DummyManager.Dummy d, @Nullable LivingEntity t, ServerLevel level, int lv) {
			Vec3 gold = new Vec3(f.centre().getX() + 0.5, 0, f.centre().getZ() + 0.5);
			Vec3 me = m.position();
			boolean onGold = Math.abs(me.x - gold.x) <= 1.3 && Math.abs(me.z - gold.z) <= 1.3;
			boolean threat = t != null && t.position().distanceTo(new Vec3(gold.x, t.getY(), gold.z)) < 3.5;
			if (!onGold && !(threat && t.distanceTo(m) < 3)) {
				look(m, t != null ? t.getEyePosition() : new Vec3(gold.x, m.getEyeY(), gold.z));
				walkStraight(m, gold, 0.26);
				hitIfReady(m, d, t, level, lv, gold);
				return;
			}
			if (threat) {
				knockGame(m, d, t, level, lv, gold, 1.5);
			} else {
				stop(m);
				if (t != null) {
					look(m, t.getEyePosition());
				}
			}
		}

		/**
		 * Knockback games: keep away from its own edge, get between the opponent and the middle, and
		 * sprint-hit them outwards (a W-tap after each hit for the next one's full knockback).
		 */
		void knockGame(Mannequin m, DummyManager.Dummy d, @Nullable LivingEntity t, ServerLevel level, int lv, Vec3 mid, double radius) {
			if (t == null) {
				walkStraight(m, mid, 0.18);
				return;
			}
			Vec3 me = new Vec3(m.getX(), 0, m.getZ());
			Vec3 tp = new Vec3(t.getX(), 0, t.getZ());
			look(m, t.getEyePosition());
			double myEdge = radius - Math.max(Math.abs(me.x - mid.x), Math.abs(me.z - mid.z));
			if (myEdge < 1.5) {
				walkStraight(m, mid, 0.28); // Too close to its own edge: back to the middle first.
				hitIfReady(m, d, t, level, lv, mid);
				return;
			}
			Vec3 out = tp.subtract(mid);
			out = out.lengthSqr() < 1e-3 ? tp.subtract(me) : out;
			out = out.lengthSqr() < 1e-3 ? new Vec3(1, 0, 0) : out.normalize();
			Vec3 spot = tp.subtract(out.scale(1.8)); // On the middle side of them.
			if (d.backOff > 0) {
				d.backOff--;
				walkStraight(m, me.add(me.subtract(tp).normalize()), 0.12);
			} else if (me.distanceTo(spot) > 0.8 && me.distanceTo(tp) > 2.2) {
				walkStraight(m, spot, 0.27);
			} else {
				walkStraight(m, tp, 0.28);
			}
			hitIfReady(m, d, t, level, lv, mid);
		}

		void walkStraight(Mannequin m, Vec3 to, double speed) {
			Vec3 dir = new Vec3(to.x - m.getX(), 0, to.z - m.getZ());
			if (dir.lengthSqr() < 0.04 || m.hurtTime != 0) {
				if (dir.lengthSqr() < 0.04) {
					stop(m);
				}
				return;
			}
			dir = dir.normalize();
			m.setDeltaMovement(dir.x * speed, m.getDeltaMovement().y, dir.z * speed);
			m.setSprinting(speed > 0.22);
		}

		/** A sprint hit, pushing them away from the middle (like a player's knockback hit). */
		void hitIfReady(Mannequin m, DummyManager.Dummy d, @Nullable LivingEntity t, ServerLevel level, int lv, Vec3 mid) {
			if (t == null || act > 0 || t.distanceTo(m) > 3.0) {
				return;
			}
			if (m.getRandom().nextDouble() > 0.5 + lv * 0.1) {
				act = 5; // Missed.
				m.swing(InteractionHand.MAIN_HAND, m.getMainHandItem().getAttackAnimation(), false);
				return;
			}
			m.swing(InteractionHand.MAIN_HAND, m.getMainHandItem().getAttackAnimation(), false);
			t.hurtServer(level, DummyManager.attack(m, level), 1f);
			Vec3 dir = new Vec3(t.getX() - m.getX(), 0, t.getZ() - m.getZ());
			if (dir.lengthSqr() > 1e-4) {
				dir = dir.normalize();
				double kb = 0.35 + lv * 0.05;
				t.push(dir.x * kb, 0.08, dir.z * kb);
				t.needsSync = true;
			}
			act = 12 - lv; // Roughly a player's clicking in these games.
			d.backOff = 3;
		}

		void snowball(Mannequin m, @Nullable LivingEntity t, ServerLevel level, int lv) {
			if (t == null) {
				stop(m);
				return;
			}
			Vec3 me = m.position();
			Vec3 tp = t.position();
			double dist = Math.hypot(tp.x - me.x, tp.z - me.z);
			Vec3 to = new Vec3(tp.x - me.x, 0, tp.z - me.z).normalize();
			Vec3 side = new Vec3(-to.z, 0, to.x).scale(strafe);
			if (--strafeFor <= 0) {
				strafe = -strafe;
				strafeFor = 15 + m.getRandom().nextInt(30);
			}
			// Dodge: a snowball coming its way within 5 blocks: step sideways.
			for (Snowball s : level.getEntitiesOfClass(Snowball.class, m.getBoundingBox().inflate(5), s -> s.getOwner() != m)) {
				Vec3 v = s.getDeltaMovement();
				Vec3 rel = me.subtract(s.position());
				if (v.lengthSqr() > 0.01 && rel.normalize().dot(v.normalize()) > 0.85 && m.getRandom().nextDouble() < 0.2 + lv * 0.15) {
					side = new Vec3(-v.z, 0, v.x).normalize();
					strafeFor = 8;
				}
			}
			Vec3 move = side.scale(0.2);
			if (dist < 5) {
				move = move.add(to.scale(-0.2));
			} else if (dist > 10) {
				move = move.add(to.scale(0.22));
			}
			Vec3 home = new Vec3(f.centre().getX() + 0.5 - me.x, 0, f.centre().getZ() + 0.5 - me.z);
			if (home.length() > f.half() - 2) {
				move = move.add(home.normalize().scale(0.15)); // Not into the walls.
			}
			if (m.hurtTime == 0) {
				m.setDeltaMovement(move.x, m.getDeltaMovement().y, move.z);
			}
			// Throw with lead (where they'll be) and a little arc for the drop.
			double flight = dist / 1.5;
			Vec3 at = t.getEyePosition().add(t.getDeltaMovement().x * flight, -0.3, t.getDeltaMovement().z * flight);
			look(m, at);
			if (act == 0 && dist < 16) {
				Snowball s = new Snowball(level, m, new ItemStack(Items.SNOWBALL));
				Vec3 d = at.subtract(m.getEyePosition());
				s.shoot(d.x, d.y + 0.5 * 0.03 * flight * flight, d.z, 1.5f, (6 - lv) * 1.4f);
				level.addFreshEntity(s);
				m.swing(InteractionHand.MAIN_HAND, m.getMainHandItem().getAttackAnimation(), false);
				act = 22 - lv * 3; // L5 about 3 a second, L1 about 1.
			}
		}
	}
}
