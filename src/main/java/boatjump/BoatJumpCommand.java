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

/**
 * /보트점프(/boatjump) 명령어. 한국어와 영어로 모두 입력할 수 있다.
 * 이름을 적지 않는 명령어는 플레이어가 지금 서 있는 마커(또는 그 마커의 그룹)를 대상으로 한다.
 */
final class BoatJumpCommand implements TabExecutor, Listener {
	/** 도움말 한 줄. 사용법과 설명, 클릭했을 때 입력창에 채울 내용. */
	private record Help(String usage, String description, String suggestion) { }

	private static final List<Help> HELP = List.of(
		new Help("/보트점프 막대기", "마커 막대기를 받습니다. 블록을 좌클릭하면 첫째 지점, 우클릭하면 둘째 지점이 지정됩니다.", "막대기"),
		new Help("/보트점프 생성 <이름>", "막대기로 선택한 범위로 마커를 만듭니다.", "생성 "),
		new Help("/보트점프 생성 그룹 <이름>", "빈 그룹을 만듭니다. 그룹에 넣은 마커들은 그룹의 설정값을 함께 씁니다.", "생성 그룹 "),
		new Help("/보트점프 삭제 <이름>", "마커를 지웁니다.", "삭제 "),
		new Help("/보트점프 삭제 그룹 <이름>", "그룹을 지웁니다. 속해 있던 마커는 그룹의 설정값을 물려받고 남습니다.", "삭제 그룹 "),
		new Help("/보트점프 리스트", "모든 마커의 이름과 그룹, 범위를 봅니다.", "리스트"),
		new Help("/보트점프 정보", "지금 서 있는 마커의 설정값을 봅니다. 마커 밖에 서 있으면 기본값을 봅니다.", "정보"),
		new Help("/보트점프 정보 <이름>", "그 마커의 설정값을 봅니다. 이름에 기본값을 적으면 기본값을 봅니다.", "정보 "),
		new Help("/보트점프 정보 그룹 <이름>", "그 그룹의 설정값과 속한 마커를 봅니다.", "정보 그룹 "),
		new Help("/보트점프 설정 <항목> <값>", "지금 서 있는 마커의 설정값을 고칩니다. 마커 밖에 서 있으면 기본값을 고칩니다.", "설정 "),
		new Help("/보트점프 설정 <이름> <항목> <값>", "그 마커의 설정값을 고칩니다. 이름에 기본값을 적으면 기본값을 고칩니다.", "설정 "),
		new Help("/보트점프 그룹 추가 <이름>", "그룹 이름을 적으면 지금 서 있는 마커를 그 그룹에 넣습니다. "
			+ "마커 이름을 적으면 그 마커를 지금 서 있는 마커의 그룹에 넣습니다.", "그룹 추가 "),
		new Help("/보트점프 그룹 제거 <이름>", "그 마커를 그룹에서 뺍니다. 마커는 그룹의 설정값을 물려받습니다.", "그룹 제거 "),
		new Help("/보트점프 그룹 리스트", "모든 그룹과 속한 마커를 봅니다.", "그룹 리스트"),
		new Help("/보트점프 그룹 정보", "지금 서 있는 마커가 속한 그룹의 설정값을 봅니다.", "그룹 정보"),
		new Help("/보트점프 그룹 설정 <항목> <값>", "지금 서 있는 마커가 속한 그룹의 설정값을 고칩니다. 그룹의 모든 마커에 적용됩니다.", "그룹 설정 "),
		new Help("/보트점프 점프블록 추가 <블록...>", "보트가 한 칸 높이일 때 뛰어넘을 블록을 추가합니다.", "점프블록 추가 "),
		new Help("/보트점프 점프블록 삭제 <블록...>", "보트가 뛰어넘을 블록을 목록에서 삭제합니다.", "점프블록 삭제 "),
		new Help("/보트점프 점프블록 리스트", "보트가 뛰어넘는 블록의 목록을 봅니다.", "점프블록 리스트"));

	/** 하위 명령어. 한국어와 영어 낱말에서 내부 이름으로. */
	private static final Map<String, String> TOP = new LinkedHashMap<>();
	/** 그룹 명령어의 동작. */
	private static final Map<String, String> GROUP_ACTIONS = new LinkedHashMap<>();
	/** 점프블록 명령어의 동작. */
	private static final Map<String, String> BLOCK_ACTIONS = new LinkedHashMap<>();
	/** 설정 항목. 한국어 이름에서 영어 이름으로. */
	private static final Map<String, String> KEY_ALIASES = new LinkedHashMap<>();
	/** 설정값에 쓰는 낱말. 한국어에서 영어로. */
	private static final Map<String, String> VALUE_ALIASES = Map.of("무작위", "random", "켜기", "true", "끄기", "false");

	static {
		put(TOP, "wand", "막대기", "wand", "markers");
		put(TOP, "create", "생성", "create");
		put(TOP, "remove", "삭제", "remove", "delete");
		put(TOP, "list", "리스트", "목록", "list");
		put(TOP, "info", "정보", "info");
		put(TOP, "set", "설정", "set");
		put(TOP, "group", "그룹", "group");
		put(TOP, "jumpblock", "점프블록", "jumpblock");

		put(GROUP_ACTIONS, "add", "추가", "add");
		put(GROUP_ACTIONS, "remove", "제거", "삭제", "remove");
		put(GROUP_ACTIONS, "list", "리스트", "목록", "list");
		put(GROUP_ACTIONS, "info", "정보", "info");
		put(GROUP_ACTIONS, "set", "설정", "set");

		put(BLOCK_ACTIONS, "add", "추가", "add");
		put(BLOCK_ACTIONS, "remove", "삭제", "제거", "remove");
		put(BLOCK_ACTIONS, "list", "리스트", "목록", "list");

		BoostSettings.KOREAN.forEach((english, korean) -> KEY_ALIASES.put(korean, english));
	}

	private static void put(Map<String, String> table, String canonical, String... words) {
		for (String word : words) {
			table.put(word, canonical);
		}
	}

	private static final Pattern NAME = Pattern.compile("[0-9A-Za-z가-힣_-]{1,32}");
	private static final String GROUP_WORD = "그룹";
	private static final String DEFAULT_WORD = "기본값";

	private final MarkerStore store;
	private final MarkerWand wand;

	BoatJumpCommand(MarkerStore store, MarkerWand wand) {
		this.store = store;
		this.wand = wand;
	}

	// ---- 낱말 해석 ----

	private static String top(String word) {
		return TOP.getOrDefault(word.toLowerCase(Locale.ROOT), "");
	}

	private static boolean isGroupWord(String word) {
		return word.equals(GROUP_WORD) || word.equalsIgnoreCase("group");
	}

	private static boolean isDefaultWord(String word) {
		return word.equals(DEFAULT_WORD) || word.equalsIgnoreCase("default");
	}

	/** 설정 항목의 영어 이름. 설정 항목이 아니면 null이다. */
	private static String key(String word) {
		String english = KEY_ALIASES.getOrDefault(word, word.toLowerCase(Locale.ROOT));
		return BoostSettings.KEYS.contains(english) ? english : null;
	}

	private static String[] values(String[] args, int from) {
		String[] tokens = Arrays.copyOfRange(args, from, args.length);
		for (int i = 0; i < tokens.length; i++) {
			tokens[i] = VALUE_ALIASES.getOrDefault(tokens[i], tokens[i]);
		}
		return tokens;
	}

	// ---- 명령어 처리 ----

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		switch (args.length == 0 ? "" : top(args[0])) {
			case "wand" -> giveWand(sender);
			case "create" -> {
				if (args.length == 3 && isGroupWord(args[1])) {
					createGroup(sender, args[2]);
				} else if (args.length == 2) {
					createMarker(sender, args[1]);
				} else {
					error(sender, "사용법: /보트점프 생성 <이름> 또는 /보트점프 생성 그룹 <이름>");
				}
			}
			case "remove" -> {
				if (args.length == 3 && isGroupWord(args[1])) {
					removeGroup(sender, args[2]);
				} else if (args.length == 2) {
					removeMarker(sender, args[1]);
				} else {
					error(sender, "사용법: /보트점프 삭제 <이름> 또는 /보트점프 삭제 그룹 <이름>");
				}
			}
			case "list" -> listMarkers(sender);
			case "info" -> {
				if (args.length == 1) {
					infoHere(sender);
				} else if (args.length == 3 && isGroupWord(args[1])) {
					infoGroup(sender, args[2]);
				} else if (args.length == 2) {
					infoMarker(sender, args[1]);
				} else {
					error(sender, "사용법: /보트점프 정보 [이름] 또는 /보트점프 정보 그룹 <이름>");
				}
			}
			case "set" -> {
				if (args.length >= 3 && key(args[1]) != null) {
					setHere(sender, key(args[1]), values(args, 2));
				} else if (args.length >= 4 && key(args[2]) != null) {
					setMarker(sender, args[1], key(args[2]), values(args, 3));
				} else {
					error(sender, "사용법: /보트점프 설정 <항목> <값> 또는 /보트점프 설정 <이름> <항목> <값>");
					error(sender, "항목: " + String.join(", ", BoostSettings.KOREAN.values()));
				}
			}
			case "group" -> group(sender, args);
			case "jumpblock" -> jumpBlock(sender, args);
			default -> help(sender);
		}
		return true;
	}

	private void help(CommandSender sender) {
		info(sender, "BoatJump 명령어 (마우스를 올리면 설명이 보이고, 클릭하면 입력창에 채워집니다)");
		for (Help line : HELP) {
			sender.sendMessage(Component.text(line.usage(), NamedTextColor.AQUA)
				.hoverEvent(HoverEvent.showText(Component.text(line.description())))
				.clickEvent(ClickEvent.suggestCommand("/보트점프 " + line.suggestion())));
		}
		sender.sendMessage(Component.text("설정 항목: ", NamedTextColor.GRAY).append(Component.join(
			net.kyori.adventure.text.JoinConfiguration.separator(Component.text(", ", NamedTextColor.GRAY)),
			BoostSettings.KEYS.stream().map(key -> Component.text(BoostSettings.KOREAN.get(key), NamedTextColor.AQUA)
				.hoverEvent(HoverEvent.showText(Component.text(BoostSettings.DESCRIPTIONS.get(key))))).toList())));
		sender.sendMessage(Component.text("값: 숫자, 무작위 [최소 최대] [틱], 켜기, 끄기", NamedTextColor.GRAY));
	}

	private void giveWand(CommandSender sender) {
		if (!(sender instanceof Player player)) {
			error(sender, "플레이어만 사용할 수 있습니다.");
			return;
		}
		player.getInventory().addItem(wand.createWand());
		info(sender, "마커 막대기를 지급했습니다. 블록을 좌클릭하면 첫째 지점, 우클릭하면 둘째 지점이 지정됩니다.");
	}

	private void createMarker(CommandSender sender, String name) {
		if (!(sender instanceof Player player)) {
			error(sender, "플레이어만 사용할 수 있습니다.");
			return;
		}
		if (!validNewName(sender, name)) {
			return;
		}
		MarkerWand.Selection selection = wand.selection(player);
		if (!selection.complete()) {
			error(sender, "먼저 마커 막대기로 두 지점을 지정해야 합니다. (/보트점프 막대기)");
			return;
		}
		store.add(new Marker(name, selection.first.getWorld().getName(),
			selection.first.getX(), selection.first.getY(), selection.first.getZ(),
			selection.second.getX(), selection.second.getY(), selection.second.getZ(), null, store.defaults().copy()));
		store.save();
		info(sender, "마커 " + name + "을(를) 만들었습니다.");
	}

	private void createGroup(CommandSender sender, String name) {
		if (!validNewName(sender, name)) {
			return;
		}
		store.addGroup(new Group(name, store.defaults().copy()));
		store.save();
		info(sender, "그룹 " + name + "을(를) 만들었습니다. 마커 위에 서서 /보트점프 그룹 추가 " + name + " 을 입력하면 그 마커가 그룹에 들어갑니다.");
	}

	private void removeMarker(CommandSender sender, String name) {
		if (store.get(name) == null) {
			error(sender, "없는 마커입니다: " + name);
			return;
		}
		store.remove(name);
		store.save();
		info(sender, "마커 " + name + "을(를) 지웠습니다.");
	}

	private void removeGroup(CommandSender sender, String name) {
		if (store.group(name) == null) {
			error(sender, "없는 그룹입니다: " + name);
			return;
		}
		int members = store.members(name).size();
		store.removeGroup(name);
		store.save();
		info(sender, "그룹 " + name + "을(를) 지웠습니다." + (members > 0 ? " 속해 있던 마커 " + members + "개는 그룹의 설정값을 물려받았습니다." : ""));
	}

	private void listMarkers(CommandSender sender) {
		if (store.all().isEmpty()) {
			info(sender, "마커가 없습니다.");
			return;
		}
		for (Marker marker : store.all()) {
			info(sender, describe(marker));
		}
	}

	private static String describe(Marker marker) {
		return "마커 " + marker.name + (marker.group != null ? " [그룹 " + marker.group + "]" : "")
			+ " " + marker.world + " (" + marker.minX + ", " + marker.minY + ", " + marker.minZ
			+ ") ~ (" + marker.maxX + ", " + marker.maxY + ", " + marker.maxZ + ")";
	}

	/** 플레이어가 지금 서 있는 마커. 마커 밖에 서 있거나 플레이어가 아니면 null이다. */
	private Marker standing(CommandSender sender) {
		return sender instanceof Player player ? store.at(player.getLocation()) : null;
	}

	private void infoHere(CommandSender sender) {
		Marker marker = standing(sender);
		if (marker == null) {
			info(sender, "마커 밖입니다. 기본값: " + store.defaults().describe());
		} else {
			showMarker(sender, marker);
		}
	}

	private void infoMarker(CommandSender sender, String name) {
		if (isDefaultWord(name)) {
			info(sender, "기본값: " + store.defaults().describe());
			return;
		}
		Marker marker = store.get(name);
		if (marker == null) {
			error(sender, "없는 마커입니다: " + name);
			return;
		}
		showMarker(sender, marker);
	}

	private void showMarker(CommandSender sender, Marker marker) {
		info(sender, describe(marker));
		info(sender, (marker.group != null ? "그룹의 설정값: " : "설정값: ") + store.settingsOf(marker).describe());
	}

	private void infoGroup(CommandSender sender, String name) {
		Group group = store.group(name);
		if (group == null) {
			error(sender, "없는 그룹입니다: " + name);
			return;
		}
		List<String> members = store.members(name).stream().map(marker -> marker.name).toList();
		info(sender, "그룹 " + name + " (마커 " + members.size() + "개" + (members.isEmpty() ? "" : ": " + String.join(", ", members)) + ")");
		info(sender, "설정값: " + group.settings.describe());
	}

	private void setHere(CommandSender sender, String key, String[] tokens) {
		if (!(sender instanceof Player)) {
			error(sender, "콘솔에서는 이름을 적어야 합니다: /보트점프 설정 <이름> <항목> <값>");
			return;
		}
		Marker marker = standing(sender);
		if (marker == null) {
			setDefault(sender, key, tokens);
		} else {
			setOn(sender, marker, key, tokens);
		}
	}

	private void setMarker(CommandSender sender, String name, String key, String[] tokens) {
		if (isDefaultWord(name)) {
			setDefault(sender, key, tokens);
			return;
		}
		Marker marker = store.get(name);
		if (marker == null) {
			error(sender, "없는 마커입니다: " + name);
			return;
		}
		setOn(sender, marker, key, tokens);
	}

	private void setDefault(CommandSender sender, String key, String[] tokens) {
		String problem = store.defaults().set(key, tokens);
		if (problem != null) {
			error(sender, problem);
			return;
		}
		store.save();
		info(sender, "기본값을 고쳤습니다: " + store.defaults().describe());
	}

	private void setOn(CommandSender sender, Marker marker, String key, String[] tokens) {
		if (marker.group != null) {
			error(sender, "마커 " + marker.name + "은(는) 그룹 " + marker.group + "에 속해 있어서 그룹의 설정값을 씁니다. "
				+ "그룹의 값을 고치려면 /보트점프 그룹 설정 <항목> <값> 을, 이 마커만 따로 고치려면 먼저 /보트점프 그룹 제거 " + marker.name + " 을 쓰십시오.");
			return;
		}
		String problem = marker.settings.set(key, tokens);
		if (problem != null) {
			error(sender, problem);
			return;
		}
		marker.customized = true;
		store.save();
		info(sender, "마커 " + marker.name + "을(를) 고쳤습니다: " + marker.settings.describe());
	}

	private void group(CommandSender sender, String[] args) {
		String action = args.length >= 2 ? GROUP_ACTIONS.getOrDefault(args[1].toLowerCase(Locale.ROOT), "") : "";
		switch (action) {
			case "add" -> {
				if (args.length == 3) {
					groupAdd(sender, args[2]);
				} else {
					error(sender, "사용법: /보트점프 그룹 추가 <그룹 이름 또는 마커 이름>");
				}
			}
			case "remove" -> {
				if (args.length == 3) {
					groupRemove(sender, args[2]);
				} else {
					error(sender, "사용법: /보트점프 그룹 제거 <마커 이름>");
				}
			}
			case "list" -> listGroups(sender);
			case "info" -> {
				if (args.length == 3) {
					infoGroup(sender, args[2]);
				} else {
					Group group = standingGroup(sender);
					if (group != null) {
						infoGroup(sender, group.name);
					}
				}
			}
			case "set" -> {
				if (args.length >= 4 && key(args[2]) != null) {
					Group group = standingGroup(sender);
					if (group != null) {
						setGroup(sender, group, key(args[2]), values(args, 3));
					}
				} else if (args.length >= 5 && key(args[3]) != null) {
					Group group = store.group(args[2]);
					if (group == null) {
						error(sender, "없는 그룹입니다: " + args[2]);
					} else {
						setGroup(sender, group, key(args[3]), values(args, 4));
					}
				} else {
					error(sender, "사용법: /보트점프 그룹 설정 <항목> <값> 또는 /보트점프 그룹 설정 <그룹> <항목> <값>");
					error(sender, "항목: " + String.join(", ", BoostSettings.KOREAN.values()));
				}
			}
			default -> error(sender, "사용법: /보트점프 그룹 <추가|제거|리스트|정보|설정> ...");
		}
	}

	/** 지금 서 있는 마커가 속한 그룹. 없으면 그 이유를 알리고 null을 돌려준다. */
	private Group standingGroup(CommandSender sender) {
		Marker marker = standing(sender);
		if (marker == null) {
			error(sender, "그룹에 속한 마커 위에 서 있어야 합니다. 그룹 이름을 직접 적어도 됩니다.");
			return null;
		}
		if (marker.group == null) {
			error(sender, "마커 " + marker.name + "은(는) 그룹에 속해 있지 않습니다.");
			return null;
		}
		return store.group(marker.group);
	}

	private void groupAdd(CommandSender sender, String name) {
		Group group = store.group(name);
		Marker target;
		if (group != null) {
			// 그룹 이름을 적었으면, 지금 서 있는 마커를 그 그룹에 넣는다.
			target = standing(sender);
			if (target == null) {
				error(sender, "그룹 " + name + "에 넣을 마커 위에 서 있어야 합니다.");
				return;
			}
		} else if (store.get(name) != null) {
			// 마커 이름을 적었으면, 그 마커를 지금 서 있는 마커의 그룹에 넣는다.
			group = standingGroup(sender);
			if (group == null) {
				return;
			}
			target = store.get(name);
		} else {
			error(sender, "그런 이름의 그룹이나 마커가 없습니다: " + name + " (그룹은 /보트점프 생성 그룹 <이름> 으로 만듭니다)");
			return;
		}
		target.group = group.name;
		store.save();
		info(sender, "마커 " + target.name + "을(를) 그룹 " + group.name + "에 넣었습니다. 이제 그룹의 설정값을 씁니다.");
	}

	private void groupRemove(CommandSender sender, String name) {
		Marker marker = store.get(name);
		if (marker == null) {
			error(sender, "없는 마커입니다: " + name);
			return;
		}
		if (marker.group == null) {
			error(sender, "마커 " + name + "은(는) 그룹에 속해 있지 않습니다.");
			return;
		}
		String group = marker.group;
		store.leaveGroup(marker);
		store.save();
		info(sender, "마커 " + name + "을(를) 그룹 " + group + "에서 뺐습니다. 그룹의 설정값을 물려받았습니다.");
	}

	private void listGroups(CommandSender sender) {
		if (store.groups().isEmpty()) {
			info(sender, "그룹이 없습니다.");
			return;
		}
		for (Group group : store.groups()) {
			List<String> members = store.members(group.name).stream().map(marker -> marker.name).toList();
			info(sender, "그룹 " + group.name + " (마커 " + members.size() + "개" + (members.isEmpty() ? "" : ": " + String.join(", ", members)) + ")");
		}
	}

	private void setGroup(CommandSender sender, Group group, String key, String[] tokens) {
		String problem = group.settings.set(key, tokens);
		if (problem != null) {
			error(sender, problem);
			return;
		}
		group.customized = true;
		store.save();
		info(sender, "그룹 " + group.name + "을(를) 고쳤습니다 (마커 " + store.members(group.name).size() + "개에 적용): " + group.settings.describe());
	}

	private void jumpBlock(CommandSender sender, String[] args) {
		String action = args.length >= 2 ? BLOCK_ACTIONS.getOrDefault(args[1].toLowerCase(Locale.ROOT), "") : "";
		if (action.equals("list") && args.length == 2) {
			info(sender, "보트가 뛰어넘는 블록: " + String.join(", ",
				store.jumpBlocks().stream().map(material -> material.getKey().getKey()).toList()));
			return;
		}
		if (!(action.equals("add") || action.equals("remove")) || args.length < 3) {
			error(sender, "사용법: /보트점프 점프블록 <추가|삭제> <블록...> 또는 /보트점프 점프블록 리스트");
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

	/** 새 마커나 그룹의 이름으로 쓸 수 있는지 확인한다. 명령어의 낱말과 헷갈리는 이름, 이미 있는 이름은 막는다. */
	private boolean validNewName(CommandSender sender, String name) {
		if (!NAME.matcher(name).matches()) {
			error(sender, "이름은 한글, 영문, 숫자, _, - 로 32자 이내여야 합니다: " + name);
			return false;
		}
		if (isGroupWord(name) || isDefaultWord(name) || key(name) != null) {
			error(sender, "명령어에서 쓰는 낱말은 이름으로 쓸 수 없습니다: " + name);
			return false;
		}
		// 그룹 추가 명령어가 이름만으로 그룹과 마커를 구분하므로, 둘이 같은 이름을 쓸 수 없다.
		if (store.get(name) != null || store.group(name) != null) {
			error(sender, "이미 있는 마커 또는 그룹의 이름입니다: " + name);
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

	// ---- 자동 완성 ----

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
		if (!lower.startsWith("/boatjump ") && !lower.startsWith("/보트점프 ") && !lower.startsWith("/boatjump:boatjump ")) {
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
		Map<String, String> options = new LinkedHashMap<>();
		String sub = top(args[0]);
		int position = args.length;

		if (position == 1) {
			options.put("막대기", "마커 막대기를 받습니다.");
			options.put("생성", "마커나 그룹을 만듭니다.");
			options.put("삭제", "마커나 그룹을 지웁니다.");
			options.put("리스트", "모든 마커를 봅니다.");
			options.put("정보", "설정값을 봅니다. 이름을 적지 않으면 지금 서 있는 마커를 봅니다.");
			options.put("설정", "설정값을 고칩니다. 이름을 적지 않으면 지금 서 있는 마커를 고칩니다.");
			options.put("그룹", "그룹에 마커를 넣거나 빼고, 그룹의 설정값을 다룹니다.");
			options.put("점프블록", "보트가 뛰어넘을 블록을 관리합니다.");
		} else if (sub.equals("create") && position == 2) {
			options.put(GROUP_WORD, "마커 대신 그룹을 만듭니다. 뒤에 그룹 이름을 적습니다.");
		} else if (sub.equals("remove") && position == 2) {
			options.put(GROUP_WORD, "마커 대신 그룹을 지웁니다. 뒤에 그룹 이름을 적습니다.");
			addMarkers(options);
		} else if (sub.equals("remove") && position == 3 && isGroupWord(args[1])) {
			addGroups(options);
		} else if (sub.equals("info") && position == 2) {
			options.put(GROUP_WORD, "마커 대신 그룹의 설정값을 봅니다. 뒤에 그룹 이름을 적습니다.");
			options.put(DEFAULT_WORD, "마커 밖에서 쓰이는 기본 설정값입니다.");
			addMarkers(options);
		} else if (sub.equals("info") && position == 3 && isGroupWord(args[1])) {
			addGroups(options);
		} else if (sub.equals("set")) {
			// 이름을 적지 않으면 항목이 둘째 자리에, 이름을 적으면 셋째 자리에 온다.
			boolean named = position >= 3 && key(args[1]) == null;
			int keyPosition = named ? 3 : 2;
			if (position == 2) {
				addKeys(options);
				options.put(DEFAULT_WORD, "마커 밖에서 쓰이는 기본 설정값을 고칩니다. 뒤에 항목과 값을 적습니다.");
				addMarkers(options);
			} else if (position == keyPosition) {
				addKeys(options);
			} else if (position == keyPosition + 1) {
				addValues(options, key(args[keyPosition - 1]));
			}
		} else if (sub.equals("group")) {
			String action = position >= 3 ? GROUP_ACTIONS.getOrDefault(args[1].toLowerCase(Locale.ROOT), "") : "";
			if (position == 2) {
				options.put("추가", "그룹 이름을 적으면 지금 서 있는 마커를 그 그룹에 넣고, 마커 이름을 적으면 그 마커를 지금 서 있는 마커의 그룹에 넣습니다.");
				options.put("제거", "마커를 그룹에서 뺍니다.");
				options.put("리스트", "모든 그룹을 봅니다.");
				options.put("정보", "지금 서 있는 마커가 속한 그룹의 설정값을 봅니다.");
				options.put("설정", "지금 서 있는 마커가 속한 그룹의 설정값을 고칩니다.");
			} else if (action.equals("add") && position == 3) {
				addGroups(options);
				addMarkers(options);
			} else if (action.equals("remove") && position == 3) {
				for (Marker marker : store.all()) {
					if (marker.group != null) {
						options.put(marker.name, "마커 [그룹 " + marker.group + "]");
					}
				}
			} else if (action.equals("info") && position == 3) {
				addGroups(options);
			} else if (action.equals("set")) {
				boolean named = position >= 4 && key(args[2]) == null;
				int keyPosition = named ? 4 : 3;
				if (position == 3) {
					addKeys(options);
					addGroups(options);
				} else if (position == keyPosition) {
					addKeys(options);
				} else if (position == keyPosition + 1) {
					addValues(options, key(args[keyPosition - 1]));
				}
			}
		} else if (sub.equals("jumpblock")) {
			String action = position >= 3 ? BLOCK_ACTIONS.getOrDefault(args[1].toLowerCase(Locale.ROOT), "") : "";
			if (position == 2) {
				options.put("추가", "보트가 뛰어넘을 블록을 추가합니다. 여러 개를 띄어 써서 한 번에 넣을 수 있습니다.");
				options.put("삭제", "보트가 뛰어넘을 블록을 목록에서 삭제합니다.");
				options.put("리스트", "보트가 뛰어넘는 블록의 목록을 봅니다.");
			} else if (action.equals("remove")) {
				store.jumpBlocks().forEach(material -> options.put(material.getKey().getKey(), null));
			} else if (action.equals("add")) {
				for (Material material : Material.values()) {
					if (material.isBlock()) {
						options.put(material.getKey().getKey(), null);
					}
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
			options.put(marker.name, describe(marker));
		}
	}

	private void addGroups(Map<String, String> options) {
		for (Group group : store.groups()) {
			options.put(group.name, "그룹 (마커 " + store.members(group.name).size() + "개)");
		}
	}

	private static void addKeys(Map<String, String> options) {
		for (String key : BoostSettings.KEYS) {
			options.put(BoostSettings.KOREAN.get(key), BoostSettings.DESCRIPTIONS.get(key));
		}
	}

	private static void addValues(Map<String, String> options, String key) {
		if (key == null) {
			return;
		}
		if (key.equals("enabled")) {
			options.put("켜기", "마커의 속도 변화를 켭니다.");
			options.put("끄기", "마커의 속도 변화를 끕니다. 튕김 관련 항목은 그대로 동작합니다.");
		} else if (!key.equals("interval")) {
			options.put("무작위", "정해진 틱마다 값이 무작위로 바뀝니다. 형식: 무작위 [최소 최대] [틱] "
				+ "(범위를 생략하면 항목별 기본 범위, 틱을 생략하면 " + Value.DEFAULT_PERIOD + "틱)");
		}
	}
}
