package boatjump;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.block.Block;
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

/**
 * 마커 막대기. 블록을 좌클릭하면 첫째 지점, 우클릭하면 둘째 지점을 지정한다.
 * 막대기를 들고 있는 동안에는 선택한 범위와 주변 마커의 범위를 입자로 보여 준다.
 */
final class MarkerWand implements Listener {
	/** 범위를 다시 그리는 간격(틱). */
	private static final int DRAW_INTERVAL_TICKS = 10;
	/** 이 거리(블록)보다 먼 마커는 그리지 않는다. */
	private static final double DRAW_DISTANCE = 96;
	/** 범위 하나를 그릴 때 쓰는 입자의 최대 개수. 범위가 크면 입자 간격을 넓혀서 이 개수에 맞춘다. */
	private static final int MAX_PARTICLES_PER_BOX = 300;

	/** 플레이어가 막대기로 고른 두 지점. 아직 고르지 않은 지점은 null이다. */
	static final class Selection {
		Block first;
		Block second;

		boolean complete() {
			return first != null && second != null && first.getWorld().equals(second.getWorld());
		}
	}

	private final MarkerStore store;
	private final NamespacedKey wandKey;
	private final Map<UUID, Selection> selections = new HashMap<>();

	MarkerWand(JavaPlugin plugin, MarkerStore store) {
		this.store = store;
		this.wandKey = new NamespacedKey(plugin, "marker_wand");
		plugin.getServer().getScheduler().runTaskTimer(plugin, this::drawAll, DRAW_INTERVAL_TICKS, DRAW_INTERVAL_TICKS);
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
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		selections.remove(event.getPlayer().getUniqueId());
	}

	private void drawAll() {
		for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
			if (isWand(player.getInventory().getItemInMainHand())) {
				draw(player);
			}
		}
	}

	private void draw(Player player) {
		Location at = player.getLocation();
		String world = at.getWorld().getName();
		Marker standing = null;

		for (Marker marker : store.all()) {
			if (!marker.world.equals(world)) {
				continue;
			}
			// 마커에서 플레이어와 가장 가까운 지점까지의 거리로 판단한다.
			double dx = at.getX() - Math.clamp(at.getX(), marker.minX, marker.maxX + 1);
			double dy = at.getY() - Math.clamp(at.getY(), marker.minY, marker.maxY + 1);
			double dz = at.getZ() - Math.clamp(at.getZ(), marker.minZ, marker.maxZ + 1);
			if (dx * dx + dy * dy + dz * dz > DRAW_DISTANCE * DRAW_DISTANCE) {
				continue;
			}
			drawBox(player, marker.minX, marker.minY, marker.minZ, marker.maxX, marker.maxY, marker.maxZ,
				colorOf(marker.colorKey()));

			// 발밑의 블록이 마커 안에 있으면, 어느 마커 위에 서 있는지 알려 준다.
			if (standing == null && (marker.contains(world, at.getBlockX(), at.getBlockY(), at.getBlockZ())
				|| marker.contains(world, at.getBlockX(), at.getBlockY() - 1, at.getBlockZ()))) {
				standing = marker;
			}
		}

		Selection selection = selections.get(player.getUniqueId());
		if (selection != null && selection.complete() && selection.first.getWorld().equals(at.getWorld())) {
			drawBox(player,
				Math.min(selection.first.getX(), selection.second.getX()),
				Math.min(selection.first.getY(), selection.second.getY()),
				Math.min(selection.first.getZ(), selection.second.getZ()),
				Math.max(selection.first.getX(), selection.second.getX()),
				Math.max(selection.first.getY(), selection.second.getY()),
				Math.max(selection.first.getZ(), selection.second.getZ()),
				Color.WHITE);
		}

		if (standing != null) {
			player.sendActionBar(Component.text(
				"마커 " + standing.name + (standing.group != null ? " [그룹 " + standing.group + "]" : "")
					+ "  " + standing.settings.describe(),
				NamedTextColor.AQUA));
		}
	}

	/** 그룹(또는 마커) 이름에 따라 항상 같은 색을 돌려준다. 같은 그룹의 마커는 같은 색으로 보인다. */
	private static Color colorOf(String key) {
		float hue = (key.hashCode() & 0xFFFF) / (float) 0x10000;
		return Color.fromRGB(java.awt.Color.HSBtoRGB(hue, 0.9f, 1.0f) & 0xFFFFFF);
	}

	/** 블록 좌표로 주어진 범위의 모서리 12개를 입자로 그린다. 그 플레이어에게만 보인다. */
	private static void drawBox(Player player, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Color color) {
		double x1 = minX;
		double y1 = minY;
		double z1 = minZ;
		double x2 = maxX + 1;
		double y2 = maxY + 1;
		double z2 = maxZ + 1;
		double total = 4 * ((x2 - x1) + (y2 - y1) + (z2 - z1));
		double step = Math.max(0.5, total / MAX_PARTICLES_PER_BOX);
		Particle.DustOptions dust = new Particle.DustOptions(color, 1.0f);

		for (double x = x1; x <= x2; x += step) {
			spawn(player, dust, x, y1, z1);
			spawn(player, dust, x, y1, z2);
			spawn(player, dust, x, y2, z1);
			spawn(player, dust, x, y2, z2);
		}
		for (double y = y1; y <= y2; y += step) {
			spawn(player, dust, x1, y, z1);
			spawn(player, dust, x1, y, z2);
			spawn(player, dust, x2, y, z1);
			spawn(player, dust, x2, y, z2);
		}
		for (double z = z1; z <= z2; z += step) {
			spawn(player, dust, x1, y1, z);
			spawn(player, dust, x1, y2, z);
			spawn(player, dust, x2, y1, z);
			spawn(player, dust, x2, y2, z);
		}
	}

	private static void spawn(Player player, Particle.DustOptions dust, double x, double y, double z) {
		player.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dust);
	}
}
