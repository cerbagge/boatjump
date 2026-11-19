package boatjump;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * 마커 막대기. 블록을 좌클릭하면 첫째 지점, 우클릭하면 둘째 지점을 지정한다.
 * 막대기를 들고 있는 동안에는 선택한 범위(흰색)와 주변 마커의 범위를 선으로 보여 준다.
 * 마커는 초록색이고, 설정값을 고친 마커는 파란색이다.
 * 선은 가늘게 늘인 블록 표시 엔티티이며, 막대기를 든 플레이어에게만 보인다.
 */
final class MarkerWand implements Listener {
	/** 범위 표시를 갱신하는 간격(틱). */
	private static final int REFRESH_INTERVAL_TICKS = 10;
	/** 이 거리(블록)보다 먼 마커는 표시하지 않는다. */
	private static final double DRAW_DISTANCE = 96;
	/** 선의 굵기(블록). */
	private static final float LINE_WIDTH = 0.04f;
	/** 설정값을 고치지 않은 마커의 색. */
	private static final Color PLAIN_COLOR = Color.LIME;
	/** 설정 명령어로 설정값을 고친 마커(또는 그런 그룹에 속한 마커)의 색. */
	private static final Color CUSTOMIZED_COLOR = Color.fromRGB(0x3399FF);

	/** 플레이어가 막대기로 고른 두 지점. 아직 고르지 않은 지점은 null이다. */
	static final class Selection {
		Block first;
		Block second;

		boolean complete() {
			return first != null && second != null && first.getWorld().equals(second.getWorld());
		}
	}

	/** 선으로 표시할 직육면체 하나. 좌표는 블록 단위이며 양 끝을 포함한다. */
	private record Box(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Color color) { }

	private final JavaPlugin plugin;
	private final MarkerStore store;
	private final NamespacedKey wandKey;
	private final Map<UUID, Selection> selections = new HashMap<>();
	/** 플레이어별로 지금 보여 주고 있는 상자와 그 상자를 이루는 선들. */
	private final Map<UUID, Map<Box, List<BlockDisplay>>> shown = new HashMap<>();

	MarkerWand(JavaPlugin plugin, MarkerStore store) {
		this.plugin = plugin;
		this.store = store;
		this.wandKey = new NamespacedKey(plugin, "marker_wand");
		plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshAll, REFRESH_INTERVAL_TICKS, REFRESH_INTERVAL_TICKS);
	}

	ItemStack createWand() {
		ItemStack wand = new ItemStack(Material.STICK);
		ItemMeta meta = wand.getItemMeta();
		meta.displayName(Component.text("BoatJump 마커 막대기", NamedTextColor.AQUA));
		meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
		wand.setItemMeta(meta);
		return wand;
	}

	Selection selection(Player player) {
		return selections.computeIfAbsent(player.getUniqueId(), id -> new Selection());
	}

	private boolean isWand(ItemStack item) {
		return item != null && item.getType() == Material.STICK && item.hasItemMeta()
			&& item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
	}

	@EventHandler
	public void onInteract(PlayerInteractEvent event) {
		if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null || !isWand(event.getItem())) {
			return;
		}
		Action action = event.getAction();
		if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
			return;
		}

		// 막대기로 블록을 부수거나 블록의 기능을 쓰지 않도록 한다.
		event.setCancelled(true);
		Block block = event.getClickedBlock();
		Selection selection = selection(event.getPlayer());
		boolean first = action == Action.LEFT_CLICK_BLOCK;
		if (first) {
			selection.first = block;
		} else {
			selection.second = block;
		}
		event.getPlayer().sendMessage(Component.text(
			(first ? "첫째" : "둘째") + " 지점: " + block.getX() + ", " + block.getY() + ", " + block.getZ(),
			NamedTextColor.AQUA));
		if (selection.complete()) {
			event.getPlayer().sendMessage(Component.text(
				"범위를 지정했습니다. /보트점프 생성 <이름> 으로 마커를 만드십시오.", NamedTextColor.GRAY));
		}
		refresh(event.getPlayer());
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		selections.remove(event.getPlayer().getUniqueId());
		hideAll(event.getPlayer().getUniqueId());
	}

	/** 플러그인이 꺼질 때, 남아 있는 선을 모두 지운다. */
	void removeAll() {
		for (UUID id : new ArrayList<>(shown.keySet())) {
			hideAll(id);
		}
	}

	private void hideAll(UUID id) {
		Map<Box, List<BlockDisplay>> boxes = shown.remove(id);
		if (boxes != null) {
			boxes.values().forEach(lines -> lines.forEach(BlockDisplay::remove));
		}
	}

	private void refreshAll() {
		for (Player player : Bukkit.getOnlinePlayers()) {
			refresh(player);
		}
	}

	/** 플레이어에게 보여야 할 상자를 다시 계산하고, 달라진 부분만 선을 지우거나 새로 만든다. */
	private void refresh(Player player) {
		if (!isWand(player.getInventory().getItemInMainHand())) {
			hideAll(player.getUniqueId());
			return;
		}

		Location at = player.getLocation();
		World world = at.getWorld();
		Map<Box, Boolean> wanted = new LinkedHashMap<>();

		for (Marker marker : store.all()) {
			if (!marker.world.equals(world.getName())) {
				continue;
			}
			// 마커에서 플레이어와 가장 가까운 지점까지의 거리로 판단한다.
			double dx = at.getX() - Math.clamp(at.getX(), marker.minX, marker.maxX + 1);
			double dy = at.getY() - Math.clamp(at.getY(), marker.minY, marker.maxY + 1);
			double dz = at.getZ() - Math.clamp(at.getZ(), marker.minZ, marker.maxZ + 1);
			if (dx * dx + dy * dy + dz * dz > DRAW_DISTANCE * DRAW_DISTANCE) {
				continue;
			}
			wanted.put(new Box(world, marker.minX, marker.minY, marker.minZ, marker.maxX, marker.maxY, marker.maxZ,
				store.customizedOf(marker) ? CUSTOMIZED_COLOR : PLAIN_COLOR), true);
		}

		Selection selection = selections.get(player.getUniqueId());
		if (selection != null && selection.complete() && selection.first.getWorld().equals(world)) {
			wanted.put(new Box(world,
				Math.min(selection.first.getX(), selection.second.getX()),
				Math.min(selection.first.getY(), selection.second.getY()),
				Math.min(selection.first.getZ(), selection.second.getZ()),
				Math.max(selection.first.getX(), selection.second.getX()),
				Math.max(selection.first.getY(), selection.second.getY()),
				Math.max(selection.first.getZ(), selection.second.getZ()),
				Color.WHITE), true);
		}

		Map<Box, List<BlockDisplay>> boxes = shown.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>());
		for (Iterator<Map.Entry<Box, List<BlockDisplay>>> it = boxes.entrySet().iterator(); it.hasNext();) {
			Map.Entry<Box, List<BlockDisplay>> entry = it.next();
			if (!wanted.containsKey(entry.getKey())) {
				entry.getValue().forEach(BlockDisplay::remove);
				it.remove();
			}
		}
		for (Box box : wanted.keySet()) {
			boxes.computeIfAbsent(box, key -> spawnBox(player, key));
		}

		// 서 있거나 바라보는 마커의 이름과 설정값을 알려 준다.
		Marker target = store.target(player);
		if (target != null) {
			player.sendActionBar(Component.text(
				"마커 " + target.name + (target.group != null ? " [그룹 " + target.group + "]" : "")
					+ "  " + store.settingsOf(target).describe(),
				NamedTextColor.AQUA));
		}
	}

	/** 상자의 모서리 12개를 선으로 만든다. */
	private List<BlockDisplay> spawnBox(Player viewer, Box box) {
		float sizeX = box.maxX - box.minX + 1;
		float sizeY = box.maxY - box.minY + 1;
		float sizeZ = box.maxZ - box.minZ + 1;
		List<BlockDisplay> lines = new ArrayList<>(12);

		for (int a = 0; a <= 1; a++) {
			for (int b = 0; b <= 1; b++) {
				// X축과 나란한 선 4개, Y축과 나란한 선 4개, Z축과 나란한 선 4개.
				lines.add(spawnLine(viewer, box, 0, a * sizeY, b * sizeZ, sizeX, 0, 0));
				lines.add(spawnLine(viewer, box, a * sizeX, 0, b * sizeZ, 0, sizeY, 0));
				lines.add(spawnLine(viewer, box, a * sizeX, b * sizeY, 0, 0, 0, sizeZ));
			}
		}
		return lines;
	}

	/**
	 * 상자의 최소 모서리에서 (offsetX, offsetY, offsetZ)만큼 떨어진 곳에서 시작하여,
	 * (lengthX, lengthY, lengthZ) 중에서 0이 아닌 축 방향으로 뻗는 선 하나를 만든다.
	 */
	private BlockDisplay spawnLine(Player viewer, Box box, float offsetX, float offsetY, float offsetZ,
		float lengthX, float lengthY, float lengthZ) {
		Location origin = new Location(box.world, box.minX, box.minY, box.minZ);
		BlockDisplay line = box.world.spawn(origin, BlockDisplay.class, display -> {
			// 다른 플레이어에게는 보이지 않게 하고, 서버를 껐다 켜도 남지 않게 한다.
			display.setVisibleByDefault(false);
			display.setPersistent(false);
			display.setBlock(Material.WHITE_CONCRETE.createBlockData());
			// 블록 뒤에 가려져도 보이도록 발광 윤곽선을 켜고, 윤곽선의 색으로 마커의 상태를 구분한다.
			display.setGlowing(true);
			display.setGlowColorOverride(box.color);
			display.setBrightness(new Display.Brightness(15, 15));
			display.setViewRange(8f);
			// 선의 중심이 모서리 위에 오도록 굵기의 절반만큼 당기고, 양 끝도 굵기의 절반만큼 더 늘인다.
			display.setTransformation(new Transformation(
				new Vector3f(offsetX - LINE_WIDTH / 2, offsetY - LINE_WIDTH / 2, offsetZ - LINE_WIDTH / 2),
				new AxisAngle4f(),
				new Vector3f(lengthX + LINE_WIDTH, lengthY + LINE_WIDTH, lengthZ + LINE_WIDTH),
				new AxisAngle4f()));
		});
		viewer.showEntity(plugin, line);
		return line;
	}
}
