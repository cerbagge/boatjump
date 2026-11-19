package boatjump;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * 플레이어가 조종하는 보트의 진행 방향에 뛰어넘을 블록(기본값은 얼음 계열)으로 된 턱이 있으면(기본값은 한 칸 높이까지),
 * 순간이동 대신 상승 속도를 매 틱 탑승자에게 전송하여 얼음 위로 올라타게 한다.
 * 얼음 윗면보다 조금 더 솟았다가 중력으로 내려앉고, 오르는 동안 수평 속도는 유지된다.
 * 또한 마커로 지정한 범위에 들어선 보트의 속도를 바꾸고, 벽과 바닥, 슬라임 블록에서 보트를 튕겨 낸다.
 */
public final class BoatJumpPlugin extends JavaPlugin implements Listener {
	/** 이 속도(블록/틱)보다 느리면 오르지 않는다. */
	private static final double MIN_SPEED = 0.03;
	/** 틱당 최대 상승 높이. 값이 클수록 순간이동에 가까워진다. */
	private static final double MAX_RISE_SPEED = 0.5;
	/** 얼음 윗면보다 더 솟아오르는 높이. 착지할 때의 통통 튀는 느낌을 결정한다. */
	private static final double OVERSHOOT = 0.1;
	/** 얼마나 앞을 미리 살필지 정하는 틱 수. 오르는 시간(3틱)에 지연 시간 여유(4틱)를 더한 값이다. */
	private static final int LOOKAHEAD_TICKS = 7;
	/** 감지한 단 위에 올라서지 못했을 때, 그 단까지 가는 데 걸리는 시간에 더해서 기다리는 여유 틱 수. */
	private static final int TIMEOUT_TICKS = 20;
	/** 오르는 도중에 올라설 단이 진행 방향에서 사라졌을 때, 포기하기 전에 기다리는 틱 수. */
	private static final int LOST_STEP_TICKS = 3;
	/** 앞쪽을 살필 때 한 번에 나아가는 거리(블록). */
	private static final double SCAN_STEP = 0.25;

	/** probe의 결과. 양수이면 오를 수 있는 턱의 높이(칸)이다. */
	private static final int NOTHING = 0;
	private static final int SOLID = -1;
	/** 보트가 받는 틱당 중력. 클라이언트가 이동 전에 빼는 값이므로 미리 더해서 보낸다. */
	private static final double BOAT_GRAVITY = 0.04;
	/** 공중에 뜬 보트가 받는 틱당 수평 감속 비율. 클라이언트가 이동 전에 곱하는 값이므로 미리 나눠서 보낸다. */
	private static final double AIR_DRAG = 0.9;

	/** 속도를 정할 때 참고하는 최근 이동 기록의 개수. */
	private static final int SPEED_SAMPLES = 5;
	/** 벽에 부딪히기 몇 틱 전에 튕길지 정하는 값. 속도 패킷이 클라이언트에 닿기까지의 지연 시간을 고려한 값이다. */
	private static final int WALL_LOOKAHEAD_TICKS = 2;
	/** 벽 쪽으로 향하는 속도가 이 값(블록/틱)보다 느리면 튕기지 않는다. 벽을 스치며 달릴 때 튕기지 않게 한다. */
	private static final double WALL_MIN_SPEED = 0.1;
	/** 벽에 튕긴 뒤 다시 튕길 수 있을 때까지의 간격(틱). */
	private static final int WALL_COOLDOWN_TICKS = 10;
	/** 바닥에서 위로 튕긴 뒤 다시 튕길 수 있을 때까지의 간격(틱). */
	private static final int SLIME_COOLDOWN_TICKS = 6;
	/** 이 속도(블록/틱)보다 느리게 떨어졌으면 탄성으로 튀어 오르지 않는다. 얼음에 올라선 뒤의 작은 낙하에 반응하지 않게 한다. */
	private static final double MIN_FALL_SPEED = 0.15;

	/** 보트 한 대의 상태. 보트가 사라지면 함께 정리된다. */
	private static final class BoatState {
		/** 최근 이동 속도. 아직 기록이 없으면 null이다. */
		double[] speeds;
		/** 직전 이동의 높이 변화량. 음수이면 떨어지는 중이었다는 뜻이다. */
		double previousDy;
		/** 직전에 있던 마커의 이름. 마커 밖이었으면 null이다. */
		String lastZone;
		int lastBoostTick = -10000;
		int lastWallTick = -10000;
		int lastSlimeTick = -10000;
	}

	private final Set<UUID> climbing = new HashSet<>();
	private final Map<Boat, BoatState> states = new WeakHashMap<>();
	private MarkerStore store;
	private MarkerWand wand;

	@Override
	public void onEnable() {
		getDataFolder().mkdirs();
		store = new MarkerStore(new java.io.File(getDataFolder(), "markers.yml"), getLogger());
		store.load();

		wand = new MarkerWand(this, store);
		getServer().getPluginManager().registerEvents(this, this);
		getServer().getPluginManager().registerEvents(wand, this);

		BoatJumpCommand command = new BoatJumpCommand(store, wand);
		getCommand("boatjump").setExecutor(command);
		getCommand("boatjump").setTabCompleter(command);
		getServer().getPluginManager().registerEvents(command, this);
	}

	@Override
	public void onDisable() {
		wand.removeAll();
	}

	@EventHandler(ignoreCancelled = true)
	public void onVehicleMove(VehicleMoveEvent event) {
		if (!(event.getVehicle() instanceof Boat boat) || climbing.contains(boat.getUniqueId())) {
			return;
		}
		if (boat.getPassengers().isEmpty() || !(boat.getPassengers().getFirst() instanceof Player rider)) {
			return;
		}

		BoatState state = states.computeIfAbsent(boat, key -> new BoatState());
		int now = Bukkit.getCurrentTick();
		Location from = event.getFrom();
		Location to = event.getTo();
		World world = to.getWorld();
		double motionX = to.getX() - from.getX();
		double motionZ = to.getZ() - from.getZ();
		double dy = to.getY() - from.getY();
		double previousDy = state.previousDy;
		state.previousDy = dy;

		// 보트 바로 아래의 블록이 마커 안에 있으면 그 마커의 설정값을 쓰고, 없으면 기본값을 쓴다.
		// 마커를 바닥이 아니라 공간으로 지정한 경우도 인정하도록, 보트가 있는 칸도 함께 확인한다.
		Block groundBlock = to.clone().subtract(0, 0.2, 0).getBlock();
		Material ground = groundBlock.getType();
		Marker marker = store.find(world.getName(), groundBlock.getX(), groundBlock.getY(), groundBlock.getZ());
		if (marker == null) {
			marker = store.find(world.getName(), to.getBlockX(), to.getBlockY(), to.getBlockZ());
		}
		BoostSettings settings = marker != null ? store.settingsOf(marker) : store.defaults();

		// 단단한 블록의 윗면에 닿아 있을 때에만 위로 튕긴다. 물에 떠 있을 때에는 튕기지 않는다.
		boolean resting = !groundBlock.isPassable() && to.getY() - (groundBlock.getY() + 1) < 0.05;
		// 마커의 슬라임 값이 있으면 블록의 종류와 관계없이 튕기고, 없으면 실제 슬라임 블록 위에서만 튕긴다.
		double launch = marker != null ? settings.get("bounce", now) : 0;
		if (launch <= 0 && ground == Material.SLIME_BLOCK) {
			launch = settings.get("slime", now);
		}
		if (resting && launch > 0) {
			// 떨어진 속도와 관계없이 정해진 세기로 튕기므로, 다시 바닥에 닿을 때마다 계속 튕긴다.
			if (now - state.lastSlimeTick >= SLIME_COOLDOWN_TICKS) {
				state.lastSlimeTick = now;
				double horizontal = Math.hypot(motionX, motionZ);
				// 튕기는 동안에는 아래의 속도 변화 처리까지 가지 못하므로, 마커에 막 들어선 경우에는 여기서 속도 변화를 함께 적용한다.
				if (marker != null && settings.enabled && !marker.name.equals(state.lastZone) && horizontal >= MIN_SPEED) {
					double target = changedSpeed(settings, horizontal, now);
					motionX *= target / horizontal;
					motionZ *= target / horizontal;
					state.lastBoostTick = now;
				}
				state.lastZone = marker != null ? marker.name : null;
				sendMotion(rider, boat, motionX, launch, motionZ);
				return;
			}
		} else if (resting && previousDy < -MIN_FALL_SPEED && dy > previousDy + 0.1) {
			// 떨어지다가 바닥에 닿았으면, 떨어지던 속도에 탄성 계수를 곱한 만큼 다시 튀어 오른다.
			double rebound = -previousDy * settings.get("elasticity", now);
			if (rebound >= 0.1) {
				sendMotion(rider, boat, motionX, rebound, motionZ);
				return;
			}
		}

		double measured = Math.hypot(motionX, motionZ);
		if (measured < MIN_SPEED) {
			return;
		}

		// 이동 패킷이 두 틱 분량으로 한꺼번에 처리되면 한 번의 이동량이 실제 속도의 두 배 가까이로 측정된다.
		// 그 값을 그대로 쓰면 오르는 동안 보트가 갑자기 빨라지므로, 최근 이동량의 중앙값을 속도로 삼는다.
		double speed = medianSpeed(state, measured);
		motionX *= speed / measured;
		motionZ *= speed / measured;

		// 공중에 떠 있는 동안에는 오르지도 가속하지도 않는다. 땅 위에 있거나 물에 떠 있을 때만 발동한다.
		if (ground.isAir()) {
			return;
		}

		double half = boat.getWidth() / 2 - 0.05;
		int feetY = (int) Math.floor(to.getY() + 0.1);

		// 넘을 수 없는 벽에 곧 부딪힐 것 같으면, 벽을 향하던 방향의 속도를 뒤집어서 튕겨 낸다.
		// 물 위에서는 튕기지 않는다. 그러지 않으면 보트를 물가에 댈 수 없게 된다.
		// 이 자리에서 보트가 한 번에 오를 수 있는 높이(칸). 0이면 오르지 않는다.
		int maxStep = (int) Math.round(settings.get("step", now));
		// 벽에서 튕기는 세기는 탄성 값을 따른다.
		double wallBounce = settings.wallBounce ? settings.get("elasticity", now) : 0;
		if (wallBounce > 0 && !groundBlock.isLiquid() && now - state.lastWallTick >= WALL_COOLDOWN_TICKS) {
			boolean hitX = Math.abs(motionX) >= WALL_MIN_SPEED && probe(world,
				to.getX() + Math.signum(motionX) * (Math.abs(motionX) * WALL_LOOKAHEAD_TICKS + 0.1), to.getZ(), half, feetY, maxStep) == SOLID;
			boolean hitZ = Math.abs(motionZ) >= WALL_MIN_SPEED && probe(world,
				to.getX(), to.getZ() + Math.signum(motionZ) * (Math.abs(motionZ) * WALL_LOOKAHEAD_TICKS + 0.1), half, feetY, maxStep) == SOLID;
			if (hitX || hitZ) {
				state.lastWallTick = now;
				sendMotion(rider, boat,
					hitX ? -motionX * wallBounce : motionX, 0, hitZ ? -motionZ * wallBounce : motionZ);
				return;
			}
		}

		// 마커 안에 들어서면 속도에 마커의 속도 값을 더한다. 매 틱 덮어쓰면 조종이 서버의 늦은 측정값에 끌려가서 어색해지므로,
		// 들어설 때 한 번만 바꾸거나 정해진 간격으로만 바꾼다.
		String zone = marker != null ? marker.name : null;
		if (marker != null && settings.enabled) {
			boolean entered = !zone.equals(state.lastZone);
			boolean repeat = settings.interval > 0 && now - state.lastBoostTick >= settings.interval;
			if (entered || repeat) {
				state.lastBoostTick = now;
				double target = changedSpeed(settings, speed, now);
				if (Math.abs(target - speed) > 0.01) {
					sendMotion(rider, boat, motionX / speed * target, 0, motionZ / speed * target);
				}
			}
		}
		state.lastZone = zone;

		double dirX = motionX / speed;
		double dirZ = motionZ / speed;
		double reach = boat.getWidth() / 2 + 0.3 + speed * LOOKAHEAD_TICKS;

		// 보트의 충돌 영역은 방향과 관계없이 회전하지 않는 정사각형이다.
		// 중심선이나 몇 개의 선만 살피면 각도에 따라 모서리가 먼저 걸리므로,
		// 정사각형 밑면을 진행 방향으로 조금씩 옮겨 가며 그 아래에 놓이는 블록을 모두 살핀다.
		// 벽에 붙어서 나란히 달릴 때 그 벽을 얼음 턱으로 오인하지 않도록, 밑면은 실제보다 조금 좁게 잡는다(half).

		for (double d = SCAN_STEP; d <= reach; d += SCAN_STEP) {
			int found = probe(world, to.getX() + dirX * d, to.getZ() + dirZ * d, half, feetY, maxStep);
			if (found == NOTHING) {
				continue;
			}
			// 얼음보다 앞에 다른 블록이 가로막고 있으면 오르지 않는다.
			if (found == SOLID) {
				return;
			}

			startClimb(boat, feetY + found, maxStep, to, motionX, motionZ);
			return;
		}
	}

	/**
	 * 중심이 (centerX, centerZ)인 보트 밑면 아래의 블록을 y 높이에서 살핀다.
	 * 오를 수 있는 턱이 하나라도 있으면 그중 가장 높은 턱의 높이(칸), 그런 턱은 없고 막힌 블록이 있으면 SOLID, 모두 비어 있으면 NOTHING이다.
	 */
	private int probe(World world, double centerX, double centerZ, double half, int y, int maxStep) {
		int result = NOTHING;
		for (int x = (int) Math.floor(centerX - half); x <= (int) Math.floor(centerX + half); x++) {
			for (int z = (int) Math.floor(centerZ - half); z <= (int) Math.floor(centerZ + half); z++) {
				Block block = world.getBlockAt(x, y, z);
				if (block.isPassable()) {
					continue;
				}
				int height = stepHeight(block, maxStep);
				if (height > 0) {
					result = Math.max(result, height);
				} else if (result == NOTHING) {
					result = SOLID;
				}
			}
		}
		return result;
	}

	/**
	 * 이 블록에서 위로 쌓인 턱의 높이(칸). 턱은 뛰어넘을 블록으로만 이루어져 있고 맨 위가 비어 있어야 한다.
	 * 다른 블록이 섞여 있거나 maxStep보다 높으면 오를 수 없으므로 0이다.
	 */
	private int stepHeight(Block bottom, int maxStep) {
		for (int height = 1; height <= maxStep; height++) {
			Block block = bottom.getRelative(0, height - 1, 0);
			if (!store.jumpBlocks().contains(block.getType())) {
				return 0;
			}
			if (block.getRelative(0, 1, 0).isPassable()) {
				return height;
			}
		}
		return 0;
	}

	private void startClimb(Boat boat, int firstTargetY, int maxStep, Location start, double motionX, double motionZ) {
		UUID id = boat.getUniqueId();
		climbing.add(id);
		double speed = Math.hypot(motionX, motionZ);
		double half = boat.getWidth() / 2 - 0.05;
		// 이 거리 안에 다음 단이 있으면 이어서 오른다. 얼음을 처음 감지할 때 쓰는 거리와 같다.
		double ahead = boat.getWidth() / 2 + 0.3 + speed * LOOKAHEAD_TICKS;
		// 단을 감지한 뒤로 이 시간 안에 그 단 위에 올라서지 못하면 포기한다. 감지한 거리를 가는 데 걸리는 시간에 여유를 더한 값이다.
		// 높은 턱은 솟아오르는 데에도 시간이 걸리므로, 그만큼을 더 기다린다.
		int patience = (int) (ahead / speed) + TIMEOUT_TICKS + (int) (maxStep / MAX_RISE_SPEED);
		// 오르기를 시작할 때 보트가 향하던 방향. 오르는 동안 보트를 돌린 만큼 진행 방향도 함께 돌리는 데 쓴다.
		float startYaw = boat.getLocation().getYaw();
		new BukkitRunnable() {
			// 올라설 높이. 오르는 도중에 다음 단이 보이면 그 단의 높이만큼 높아진다.
			private int targetY = firstTargetY;
			// 서버가 아는 보트 위치는 지연 시간만큼 늦으므로, 오르는 동안의 높이는 보낸 속도를 직접 누적해서 추정한다.
			private double predictedY = start.getY();
			private int ticks;
			// 마지막으로 단을 감지한 틱.
			private int lastStepTick;
			// 착지 단계에 들어선 뒤로 지난 틱 수. 0이면 아직 착지 단계가 아니다.
			private int landingTicks;
			// 올라설 단이 진행 방향에 보이지 않은 채로 지난 틱 수.
			private int lostTicks;

			@Override
			public void run() {
				Player rider = boat.isValid() && !boat.getPassengers().isEmpty()
					&& boat.getPassengers().getFirst() instanceof Player player ? player : null;
				if (rider == null) {
					finish();
					return;
				}
				ticks++;

				// 공중에 뜬 뒤에는 감속으로 속도를 잃지 않도록 수평 속도를 보정한다.
				// 첫 틱에는 보트가 아직 바닥에 붙어 있어서 공중 감속을 받지 않으므로, 보정하면 오히려 빨라진다.
				double drag = ticks == 1 ? 1 : AIR_DRAG;
				// 처음의 진행 방향을 그대로 유지하면, 오르는 동안 보트를 돌려도 원래 방향으로 밀려서 옆으로 미끄러진다.
				// 그래서 오르기를 시작한 뒤로 보트를 돌린 각도만큼 진행 방향을 함께 돌린다.
				Location actual = boat.getLocation();
				double turned = Math.toRadians(actual.getYaw() - startYaw);
				double nowX = motionX * Math.cos(turned) - motionZ * Math.sin(turned);
				double nowZ = motionX * Math.sin(turned) + motionZ * Math.cos(turned);
				double sendX = nowX / drag;
				double sendZ = nowZ / drag;

				// 착지 단계에서는 아래로 누르지 않고, 중력으로 떨어질 때와 똑같은 속도를 보내서 자연스럽게 내려앉게 한다.
				// 떠 있는 높이(0.1블록)는 두 틱이면 다 내려오므로, 두 틱 동안 수평 속도만 유지해 주고 끝낸다.
				if (landingTicks > 0) {
					sendMotion(rider, boat, sendX, -BOAT_GRAVITY * (landingTicks - 1), sendZ);
					if (++landingTicks > 2) {
						finish();
					}
					return;
				}

				// 계단을 미리 끝까지 계산해 두면, 도중에 방향을 틀거나 벽에 막혔을 때 계산이 어긋나서 허공에 떠 있게 된다.
				// 그래서 매 틱 실제 위치와 지금의 진행 방향을 기준으로, 올라설 높이에 다음 단이 보이면 그때 한 단을 더 올린다.
				for (double d = SCAN_STEP; d <= ahead; d += SCAN_STEP) {
					int found = probe(actual.getWorld(),
						actual.getX() + nowX / speed * d, actual.getZ() + nowZ / speed * d, half, targetY, maxStep);
					if (found > 0) {
						targetY += found;
						lastStepTick = ticks;
					}
					if (found != NOTHING) {
						break;
					}
				}

				// 방향을 틀어서 올라설 단이 더 이상 진행 방향에 없으면, 떠서 기다릴 이유가 없으므로 곧바로 내려온다.
				// 서버가 아는 위치와 방향이 실제보다 조금 늦으므로, 몇 틱 연속으로 보이지 않을 때에만 포기한다.
				boolean stepAhead = false;
				for (double d = 0; d <= ahead && !stepAhead; d += SCAN_STEP) {
					stepAhead = probe(actual.getWorld(),
						actual.getX() + nowX / speed * d, actual.getZ() + nowZ / speed * d, half, targetY - 1, 1) > 0;
				}
				lostTicks = stepAhead ? 0 : lostTicks + 1;
				if (lostTicks > LOST_STEP_TICKS) {
					finish();
					return;
				}

				if (ticks - lastStepTick > patience) {
					finish();
					return;
				}

				double rise = Math.clamp(targetY - predictedY + OVERSHOOT, 0, MAX_RISE_SPEED);
				// 부동소수점 오차 때문에 정확히 0이 되지 않으므로, 충분히 작으면 다 올라온 것으로 본다.
				if (rise >= 1.0E-6) {
					predictedY += rise;
					sendMotion(rider, boat, sendX, rise + BOAT_GRAVITY, sendZ);
					return;
				}

				double height = actual.getY() - targetY;

				// 실제 보트가 올라설 블록 위에 걸쳐 있으면 착지 단계로 넘어간다.
				if (height >= -0.05 && isSupported(boat, actual, targetY)) {
					landingTicks = 1;
					sendMotion(rider, boat, sendX, 0, sendZ);
					landingTicks++;
					return;
				}

				// 패킷이 겹치거나 늦게 도착해서 높이가 어긋났으면, 실제 높이를 보고 목표 높이로 천천히 되돌린다.
				double correction = Math.clamp((OVERSHOOT - height) * 0.3, -0.1, 0.2);
				sendMotion(rider, boat, sendX, correction + BOAT_GRAVITY, sendZ);
			}

			private void finish() {
				climbing.remove(id);
				cancel();
			}
		}.runTaskTimer(this, 0, 1);
	}

	/**
	 * 보트의 속도를 탑승자의 클라이언트에만 직접 전송한다.
	 * Boat#setVelocity를 쓰면 서버가 다음 틱에 속도를 0으로 되돌리면서 그 0도 클라이언트에 보내기 때문에,
	 * 오르기가 끝난 직후에 보트가 멈춰 버린다. 그래서 서버 쪽 속도는 건드리지 않는다.
	 */
	private static void sendMotion(Player rider, Boat boat, double x, double y, double z) {
		((CraftPlayer) rider).getHandle().connection.send(
			new ClientboundSetEntityMotionPacket(boat.getEntityId(), new Vec3(x, y, z)));
	}

	/**
	 * 마커에 들어선 보트의 새 속도(블록/틱). 지금 속도에 마커의 속도 값을 더한다.
	 * 설정값은 초당 블록 수이므로 20으로 나누어 틱당 블록 수로 바꾼다.
	 * 느려지는 경우에는 0 아래로 내려가지 않게 하고, 빨라지는 경우에는 서버가 보트를 되돌려 놓는 속도를 넘지 않게 한다.
	 * 이 한계 때문에 이미 더 빠른 보트가 느려지는 일은 없다.
	 */
	private static double changedSpeed(BoostSettings settings, double speed, int now) {
		double changed = speed + settings.get("speed", now) / 20.0;
		if (changed <= speed) {
			return Math.max(0, changed);
		}
		return Math.max(speed, Math.min(changed, BoostSettings.SERVER_SPEED_LIMIT));
	}

	/** 이번 이동량을 기록에 추가하고, 최근 이동량의 중앙값을 돌려준다. */
	private static double medianSpeed(BoatState state, double measured) {
		double[] samples = state.speeds;
		if (samples == null) {
			samples = new double[SPEED_SAMPLES];
			Arrays.fill(samples, measured);
			state.speeds = samples;
		} else {
			System.arraycopy(samples, 1, samples, 0, SPEED_SAMPLES - 1);
			samples[SPEED_SAMPLES - 1] = measured;
		}

		double[] sorted = samples.clone();
		Arrays.sort(sorted);
		return sorted[SPEED_SAMPLES / 2];
	}

	/**
	 * 보트 밑면이 목표 높이 바로 아래의 블록에 실제로 걸쳐 있는지 확인한다.
	 * 밑면의 가장자리가 블록에 닿기만 한 상태를 걸친 것으로 판정하면, 내려앉다가 블록 옆으로 떨어져서 옆면에 걸린다.
	 * 그래서 밑면을 0.1블록 좁게 잡아서, 그만큼은 확실히 겹쳐 있을 때에만 걸친 것으로 본다.
	 */
	private static boolean isSupported(Boat boat, Location at, double targetY) {
		double half = boat.getWidth() / 2 - 0.1;
		int y = (int) Math.floor(targetY - 0.5);
		for (int x = (int) Math.floor(at.getX() - half); x <= (int) Math.floor(at.getX() + half); x++) {
			for (int z = (int) Math.floor(at.getZ() - half); z <= (int) Math.floor(at.getZ() + half); z++) {
				if (!at.getWorld().getBlockAt(x, y, z).isPassable()) {
					return true;
				}
			}
		}
		return false;
	}
}
