package boatjump;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/** /boatjump 명령어. 마커 막대기 지급, 마커와 그룹 관리, 설정값 수정, 뛰어넘을 블록 관리를 맡는다. */
final class BoatJumpCommand implements TabExecutor, Listener {
	/** 하위 명령어 하나의 사용법과 설명. 도움말과 자동 완성의 설명에 함께 쓴다. */
	private record Sub(String usage, String description) { }

	private static final Map<String, Sub> SUBCOMMANDS = new LinkedHashMap<>();

	static {
		SUBCOMMANDS.put("markers", new Sub("/boatjump markers",
			"마커 막대기를 받습니다. 블록을 좌클릭하면 첫째 지점, 우클릭하면 둘째 지점이 지정됩니다."));
		SUBCOMMANDS.put("create", new Sub("/boatjump create <이름> [그룹]",
			"막대기로 선택한 범위로 마커를 만듭니다. 그룹을 적으면 그 그룹에 넣습니다."));
		SUBCOMMANDS.put("remove", new Sub("/boatjump remove <이름>",
			"마커를 지웁니다."));
		SUBCOMMANDS.put("list", new Sub("/boatjump list",
			"모든 마커의 이름과 그룹, 범위를 봅니다."));
		SUBCOMMANDS.put("info", new Sub("/boatjump info <이름|default>",
			"마커의 설정값을 봅니다. default를 적으면 마커 밖에서 쓰이는 기본값을 봅니다."));
		SUBCOMMANDS.put("set", new Sub("/boatjump set <이름|default> <항목> <값>",
			"설정값을 고칩니다. 그룹에 속한 마커를 고치면 같은 그룹의 마커가 모두 바뀝니다. "
				+ "값에는 숫자, 또는 random [최소 최대] [틱]을 쓸 수 있습니다."));
		SUBCOMMANDS.put("group", new Sub("/boatjump group <이름> <그룹|none>",
			"마커의 그룹을 지정하거나 해제합니다. 없는 마커 이름이면 선택한 범위로 새로 만듭니다."));
		SUBCOMMANDS.put("jumpblock", new Sub("/boatjump jumpblock <add|remove|list> [블록...]",
			"보트가 한 칸 높이일 때 뛰어넘을 블록을 추가하거나, 삭제하거나, 목록을 봅니다."));
	}

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
		info(sender, "BoatJump 명령어 (마우스를 올리면 설명이 보이고, 클릭하면 입력창에 채워집니다)");
		SUBCOMMANDS.forEach((name, sub) -> sender.sendMessage(Component.text(sub.usage(), NamedTextColor.AQUA)
			.hoverEvent(HoverEvent.showText(Component.text(sub.description())))
			.clickEvent(ClickEvent.suggestCommand("/boatjump " + name + " "))));
		sender.sendMessage(Component.text("설정 항목: ", NamedTextColor.GRAY).append(Component.join(
			net.kyori.adventure.text.JoinConfiguration.separator(Component.text(", ", NamedTextColor.GRAY)),
			BoostSettings.KEYS.stream().map(key -> Component.text(key, NamedTextColor.AQUA)
				.hoverEvent(HoverEvent.showText(Component.text(BoostSettings.DESCRIPTIONS.get(key))))).toList())));
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
		return List.copyOf(suggest(args).keySet());
	}

	/**
	 * 자동 완성 목록의 각 항목에 한국어 설명을 붙인다. 입력창의 목록에서 항목에 마우스를 올리면 보인다.
	 * 이 이벤트는 서버의 주 스레드가 아닌 곳에서 불릴 수 있으므로, 그 사이에 마커가 바뀌어서 읽기에 실패하면 설명 없는 기본 목록에 맡긴다.
	 */
	@EventHandler
	public void onAsyncTabComplete(AsyncTabCompleteEvent event) {
		String buffer = event.getBuffer();
		if (!event.isCommand() || !event.getSender().hasPermission("boatjump.admin")) {
			return;
		}
		String lower = buffer.toLowerCase(Locale.ROOT);
		if (!lower.startsWith("/boatjump ") && !lower.startsWith("/boatjump:boatjump ")) {
			return;
		}

		String[] args = buffer.substring(buffer.indexOf(' ') + 1).split(" ", -1);
		try {
			List<AsyncTabCompleteEvent.Completion> completions = new ArrayList<>();
			suggest(args).forEach((option, description) -> completions.add(description == null
				? AsyncTabCompleteEvent.Completion.completion(option)
				: AsyncTabCompleteEvent.Completion.completion(option, Component.text(description))));
			event.completions(completions);
			event.setHandled(true);
		} catch (ConcurrentModificationException e) {
			// 읽는 도중에 마커가 바뀌었다. 설명 없는 기본 자동 완성에 맡긴다.
		}
	}

	/** 지금까지 입력한 내용에 이어서 올 수 있는 항목과 그 설명. 설명이 없는 항목은 값이 null이다. */
	private Map<String, String> suggest(String[] args) {
		String sub = args[0].toLowerCase(Locale.ROOT);
		Map<String, String> options = new LinkedHashMap<>();
		if (args.length == 1) {
			SUBCOMMANDS.forEach((name, info) -> options.put(name, info.description()));
		} else if (args.length == 2) {
			switch (sub) {
				case "remove", "group" -> addMarkers(options);
				case "info", "set" -> {
					options.put(DEFAULT, "마커 밖에서 쓰이는 기본 설정값입니다. 벽 튕김, 탄성, 슬라임은 마커 밖에서도 이 값으로 동작합니다.");
					addMarkers(options);
				}
				case "jumpblock" -> {
					options.put("add", "보트가 뛰어넘을 블록을 추가합니다. 여러 개를 띄어 써서 한 번에 넣을 수 있습니다.");
					options.put("remove", "보트가 뛰어넘을 블록을 목록에서 삭제합니다.");
					options.put("list", "보트가 뛰어넘는 블록의 목록을 봅니다.");
				}
				default -> { }
			}
		} else if (args.length == 3 && sub.equals("set")) {
			BoostSettings.KEYS.forEach(key -> options.put(key, BoostSettings.DESCRIPTIONS.get(key)));
		} else if (args.length == 4 && sub.equals("set")) {
			if (args[2].equalsIgnoreCase("enabled")) {
				options.put("true", "마커의 속도 변화를 켭니다.");
				options.put("false", "마커의 속도 변화를 끕니다. 튕김 관련 항목은 그대로 동작합니다.");
			} else if (!args[2].equalsIgnoreCase("interval")) {
				options.put("random", "정해진 틱마다 값이 무작위로 바뀝니다. 형식: random [최소 최대] [틱] "
					+ "(범위를 생략하면 항목별 기본 범위, 틱을 생략하면 " + Value.DEFAULT_PERIOD + "틱)");
			}
		} else if (args.length == 3 && (sub.equals("group") || sub.equals("create"))) {
			for (String group : store.groups()) {
				options.put(group, "그룹 " + group + " (마커 " + store.members(group).size() + "개)");
			}
			if (sub.equals("group")) {
				options.put("none", "그룹에 넣지 않습니다. 이미 그룹에 속한 마커이면 그룹을 해제합니다.");
			}
		} else if (sub.equals("jumpblock") && args[1].equalsIgnoreCase("remove")) {
			store.jumpBlocks().forEach(material -> options.put(material.getKey().getKey(), null));
		} else if (sub.equals("jumpblock") && args[1].equalsIgnoreCase("add")) {
			for (Material material : Material.values()) {
				if (material.isBlock()) {
					options.put(material.getKey().getKey(), null);
				}
			}
		}

		String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
		Map<String, String> matching = new LinkedHashMap<>();
		for (Map.Entry<String, String> option : options.entrySet()) {
			if (matching.size() < 50 && option.getKey().toLowerCase(Locale.ROOT).startsWith(typed)) {
				matching.put(option.getKey(), option.getValue());
			}
		}
		return matching;
	}

	private void addMarkers(Map<String, String> options) {
		for (Marker marker : store.all()) {
			options.put(marker.name, "마커" + (marker.group != null ? " [그룹 " + marker.group + "]" : "")
				+ " (" + marker.minX + ", " + marker.minY + ", " + marker.minZ
				+ ") ~ (" + marker.maxX + ", " + marker.maxY + ", " + marker.maxZ + ")");
		}
	}
}
