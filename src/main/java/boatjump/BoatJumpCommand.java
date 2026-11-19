package boatjump;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** /boatjump 명령어. 마커 막대기 지급, 마커와 그룹 관리, 설정값 수정, 뛰어넘을 블록 관리를 맡는다. */
final class BoatJumpCommand implements TabExecutor {
	private static final List<String> SUBCOMMANDS =
		List.of("markers", "create", "remove", "list", "info", "set", "group", "jumpblock");
	private static final Pattern NAME = Pattern.compile("[0-9A-Za-z가-힣_-]{1,32}");
	/** 기본 설정값을 가리키는 예약된 이름. */
	private static final String DEFAULT = "default";

	private final MarkerStore store;
	private final MarkerWand wand;

	BoatJumpCommand(MarkerStore store, MarkerWand wand) {
		this.store = store;
		this.wand = wand;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
		switch (sub) {
			case "markers" -> giveWand(sender);
			case "create" -> create(sender, args);
			case "remove" -> remove(sender, args);
			case "list" -> list(sender);
			case "info" -> info(sender, args);
			case "set" -> set(sender, args);
			case "group" -> group(sender, args);
			case "jumpblock" -> jumpBlock(sender, args);
			default -> help(sender);
		}
		return true;
	}

	private void help(CommandSender sender) {
		info(sender, "/boatjump markers - 마커 막대기를 받습니다. 좌클릭은 첫째 지점, 우클릭은 둘째 지점입니다.");
		info(sender, "/boatjump create <이름> [그룹] - 선택한 범위로 마커를 만듭니다.");
		info(sender, "/boatjump remove <이름> - 마커를 지웁니다.");
		info(sender, "/boatjump list - 마커 목록을 봅니다.");
		info(sender, "/boatjump info <이름|default> - 설정값을 봅니다.");
		info(sender, "/boatjump set <이름|default> <항목> <값> - 설정값을 고칩니다. 같은 그룹의 마커도 함께 바뀝니다.");
		info(sender, "  항목: " + String.join(", ", BoostSettings.KEYS));
		info(sender, "  값: 숫자, 또는 random [최소 최대] [틱] (틱을 생략하면 " + Value.DEFAULT_PERIOD + "틱)");
		info(sender, "/boatjump group <이름> <그룹|none> - 마커의 그룹을 지정하거나 해제합니다. 없는 이름이면 선택한 범위로 새로 만듭니다.");
		info(sender, "/boatjump jumpblock <add|remove|list> [블록...] - 보트가 뛰어넘을 블록을 관리합니다.");
	}

	private void giveWand(CommandSender sender) {
		if (!(sender instanceof Player player)) {
			error(sender, "플레이어만 사용할 수 있습니다.");
			return;
		}
		player.getInventory().addItem(wand.createWand());
		info(sender, "마커 막대기를 지급했습니다. 블록을 좌클릭하면 첫째 지점, 우클릭하면 둘째 지점이 지정됩니다.");
	}

	private void create(CommandSender sender, String[] args) {
		if (!(sender instanceof Player player)) {
			error(sender, "플레이어만 사용할 수 있습니다.");
			return;
		}
		if (args.length < 2 || args.length > 3) {
			error(sender, "사용법: /boatjump create <이름> [그룹]");
			return;
		}
		String name = args[1];
		String group = args.length == 3 ? args[2] : null;
		if (!validName(sender, name) || (group != null && !validName(sender, group))) {
			return;
		}
		if (store.get(name) != null) {
			error(sender, "이미 있는 마커 이름입니다: " + name);
			return;
		}
		createFromSelection(player, name, group);
	}

	/** 플레이어가 막대기로 선택한 범위로 마커를 만든다. 범위를 아직 다 지정하지 않았으면 안내만 한다. */
	private void createFromSelection(Player player, String name, String group) {
		MarkerWand.Selection selection = wand.selection(player);
		if (!selection.complete()) {
			error(player, "먼저 마커 막대기로 두 지점을 지정해야 합니다. (/boatjump markers)");
			return;
		}

		// 그룹에 이미 마커가 있으면 그 그룹의 설정값을 따르고, 없으면 기본 설정값에서 시작한다.
		Marker marker = new Marker(name, selection.first.getWorld().getName(),
			selection.first.getX(), selection.first.getY(), selection.first.getZ(),
			selection.second.getX(), selection.second.getY(), selection.second.getZ(), group, store.defaults().copy());
		if (group != null && !store.members(group).isEmpty()) {
			Marker member = store.members(group).getFirst();
			marker.settings = member.settings.copy();
			marker.customized = member.customized;
		}
		store.add(marker);
		store.save();
		info(player, "마커 " + name + "을(를) 만들었습니다." + (group != null ? " 그룹: " + group : ""));
	}

	private void remove(CommandSender sender, String[] args) {
		if (args.length != 2) {
			error(sender, "사용법: /boatjump remove <이름>");
			return;
		}
		if (store.get(args[1]) == null) {
			error(sender, "없는 마커입니다: " + args[1]);
			return;
		}
		store.remove(args[1]);
		store.save();
		info(sender, "마커 " + args[1] + "을(를) 지웠습니다.");
	}

	private void list(CommandSender sender) {
		if (store.all().isEmpty()) {
			info(sender, "마커가 없습니다.");
			return;
		}
		for (Marker marker : store.all()) {
			info(sender, marker.name + (marker.group != null ? " [그룹 " + marker.group + "]" : "")
				+ " " + marker.world + " (" + marker.minX + ", " + marker.minY + ", " + marker.minZ
				+ ") ~ (" + marker.maxX + ", " + marker.maxY + ", " + marker.maxZ + ")");
		}
	}

	private void info(CommandSender sender, String[] args) {
		if (args.length != 2) {
			error(sender, "사용법: /boatjump info <이름|default>");
			return;
		}
		if (args[1].equals(DEFAULT)) {
			info(sender, "기본 설정값: " + store.defaults().describe());
			return;
		}
		Marker marker = store.get(args[1]);
		if (marker == null) {
			error(sender, "없는 마커입니다: " + args[1]);
			return;
		}
		info(sender, "마커 " + marker.name + (marker.group != null ? " [그룹 " + marker.group + "]" : "")
			+ ": " + marker.settings.describe());
	}

	private void set(CommandSender sender, String[] args) {
		if (args.length < 4) {
			error(sender, "사용법: /boatjump set <이름|default> <항목> <값>");
			return;
		}
		String key = args[2].toLowerCase(Locale.ROOT);
		String[] tokens = Arrays.copyOfRange(args, 3, args.length);

		if (args[1].equals(DEFAULT)) {
			String problem = store.defaults().set(key, tokens);
			if (problem != null) {
				error(sender, problem);
				return;
			}
			store.save();
			info(sender, "기본 설정값을 고쳤습니다: " + store.defaults().describe());
			return;
		}

		Marker marker = store.get(args[1]);
		if (marker == null) {
			error(sender, "없는 마커입니다: " + args[1]);
			return;
		}
		// 그룹에 속한 마커를 고치면, 같은 그룹의 모든 마커에 똑같이 적용한다.
		List<Marker> targets = marker.group != null ? store.members(marker.group) : List.of(marker);
		for (Marker target : targets) {
			String problem = target.settings.set(key, tokens);
			if (problem != null) {
				error(sender, problem);
				return;
			}
			target.customized = true;
		}
		store.save();
		info(sender, (marker.group != null ? "그룹 " + marker.group + "의 마커 " + targets.size() + "개를" : "마커 " + marker.name + "을(를)")
			+ " 고쳤습니다: " + marker.settings.describe());
	}

	private void group(CommandSender sender, String[] args) {
		if (args.length != 3) {
			error(sender, "사용법: /boatjump group <이름> <그룹|none>");
			return;
		}
		Marker marker = store.get(args[1]);
		if (marker == null) {
			// 아직 없는 마커이면, 막대기로 선택한 범위로 새로 만든다.
			boolean none = args[2].equalsIgnoreCase("none");
			if (!(sender instanceof Player player)) {
				error(sender, "없는 마커입니다: " + args[1]);
			} else if (validName(sender, args[1]) && (none || validName(sender, args[2]))) {
				createFromSelection(player, args[1], none ? null : args[2]);
			}
			return;
		}
		if (args[2].equalsIgnoreCase("none")) {
			marker.group = null;
			store.save();
			info(sender, "마커 " + marker.name + "의 그룹을 해제했습니다. 설정값은 그대로 유지됩니다.");
			return;
		}
		if (!validName(sender, args[2])) {
			return;
		}
		// 이미 마커가 있는 그룹에 들어가면, 그 그룹의 설정값으로 맞춘다.
		List<Marker> members = store.members(args[2]);
		members.remove(marker);
		if (!members.isEmpty()) {
			marker.settings = members.getFirst().settings.copy();
			marker.customized = members.getFirst().customized;
		}
		marker.group = args[2];
		store.save();
		info(sender, "마커 " + marker.name + "을(를) 그룹 " + marker.group + "에 넣었습니다."
			+ (members.isEmpty() ? "" : " 설정값을 그룹에 맞추었습니다."));
	}

	private void jumpBlock(CommandSender sender, String[] args) {
		String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
		if (action.equals("list") && args.length == 2) {
			info(sender, "보트가 뛰어넘는 블록: " + String.join(", ",
				store.jumpBlocks().stream().map(material -> material.getKey().getKey()).toList()));
			return;
		}
		if (!(action.equals("add") || action.equals("remove")) || args.length < 3) {
			error(sender, "사용법: /boatjump jumpblock <add|remove|list> [블록...]");
			return;
		}

		// 하나라도 잘못된 이름이 있으면 아무것도 바꾸지 않는다.
		List<Material> materials = new ArrayList<>();
		for (int i = 2; i < args.length; i++) {
			Material material = Material.matchMaterial(args[i]);
			if (material == null || !material.isBlock()) {
				error(sender, "블록이 아닙니다: " + args[i]);
				return;
			}
			materials.add(material);
		}
		if (action.equals("add")) {
			store.jumpBlocks().addAll(materials);
		} else {
			materials.forEach(store.jumpBlocks()::remove);
		}
		store.save();
		info(sender, "블록 " + materials.size() + "개를 " + (action.equals("add") ? "추가" : "삭제")
			+ "했습니다. 현재 " + store.jumpBlocks().size() + "개입니다.");
	}

	private boolean validName(CommandSender sender, String name) {
		if (name.equals(DEFAULT) || name.equalsIgnoreCase("none") || !NAME.matcher(name).matches()) {
			error(sender, "이름은 한글, 영문, 숫자, _, - 로 32자 이내여야 하며, default와 none은 쓸 수 없습니다: " + name);
			return false;
		}
		return true;
	}

	private static void info(CommandSender sender, String text) {
		sender.sendMessage(Component.text(text, NamedTextColor.AQUA));
	}

	private static void error(CommandSender sender, String text) {
		sender.sendMessage(Component.text(text, NamedTextColor.RED));
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
		String sub = args[0].toLowerCase(Locale.ROOT);
		List<String> options = new ArrayList<>();
		if (args.length == 1) {
			options.addAll(SUBCOMMANDS);
		} else if (args.length == 2) {
			switch (sub) {
				case "remove", "group" -> store.all().forEach(marker -> options.add(marker.name));
				case "info", "set" -> {
					options.add(DEFAULT);
					store.all().forEach(marker -> options.add(marker.name));
				}
				case "jumpblock" -> options.addAll(List.of("add", "remove", "list"));
				default -> { }
			}
		} else if (args.length == 3 && sub.equals("set")) {
			options.addAll(BoostSettings.KEYS);
		} else if (args.length == 4 && sub.equals("set")) {
			options.addAll(args[2].equalsIgnoreCase("enabled") ? List.of("true", "false") : List.of("random"));
		} else if (args.length == 3 && (sub.equals("group") || sub.equals("create"))) {
			options.addAll(store.groups());
			if (sub.equals("group")) {
				options.add("none");
			}
		} else if (sub.equals("jumpblock") && args[1].equalsIgnoreCase("remove")) {
			store.jumpBlocks().forEach(material -> options.add(material.getKey().getKey()));
		} else if (sub.equals("jumpblock") && args[1].equalsIgnoreCase("add")) {
			for (Material material : Material.values()) {
				if (material.isBlock()) {
					options.add(material.getKey().getKey());
				}
			}
		}

		String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
		return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed)).limit(50).toList();
	}
}
