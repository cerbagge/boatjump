package boatjump;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** 마커와 기본 설정값, 보트가 뛰어넘을 블록 목록을 보관하고, markers.yml 파일에 저장한다. */
final class MarkerStore {
	private final File file;
	private final Logger logger;
	private final Map<String, Marker> markers = new LinkedHashMap<>();
	/** 어느 마커에도 속하지 않는 곳에 적용하는 설정값. 속도 배율은 마커 안에서만 쓰이고, 튕김 관련 값은 마커 밖에서도 쓰인다. */
	private BoostSettings defaults = new BoostSettings();
	/** 보트가 한 칸 높이일 때 뛰어넘는 블록. 처음에는 얼음 계열 블록이 들어 있다. */
	private final Set<Material> jumpBlocks = new LinkedHashSet<>();

	MarkerStore(File file, Logger logger) {
		this.file = file;
		this.logger = logger;
	}

	void load() {
		markers.clear();
		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
		defaults = BoostSettings.load(yaml.getConfigurationSection("default"));

		jumpBlocks.clear();
		if (yaml.isList("jumpblocks")) {
			for (String name : yaml.getStringList("jumpblocks")) {
				Material material = Material.matchMaterial(name);
				if (material != null && material.isBlock()) {
					jumpBlocks.add(material);
				} else {
					logger.warning("markers.yml의 jumpblocks 항목 '" + name + "'은 블록이 아니어서 건너뜁니다.");
				}
			}
		} else {
			jumpBlocks.addAll(Tag.ICE.getValues());
		}

		ConfigurationSection section = yaml.getConfigurationSection("markers");
		if (section == null) {
			return;
		}
		for (String name : section.getKeys(false)) {
			ConfigurationSection entry = section.getConfigurationSection(name);
			List<Integer> min = entry.getIntegerList("min");
			List<Integer> max = entry.getIntegerList("max");
			if (entry.getString("world") == null || min.size() != 3 || max.size() != 3) {
				logger.warning("markers.yml의 마커 '" + name + "'은 형식이 올바르지 않아서 건너뜁니다.");
				continue;
			}
			Marker marker = new Marker(name, entry.getString("world"),
				min.get(0), min.get(1), min.get(2), max.get(0), max.get(1), max.get(2),
				entry.getString("group"), BoostSettings.load(entry.getConfigurationSection("settings")));
			marker.customized = entry.getBoolean("customized");
			markers.put(name, marker);
		}
	}

	void save() {
		YamlConfiguration yaml = new YamlConfiguration();
		defaults.save(yaml.createSection("default"));
		yaml.set("jumpblocks", jumpBlocks.stream().map(material -> material.getKey().toString()).toList());
		ConfigurationSection section = yaml.createSection("markers");
		for (Marker marker : markers.values()) {
			ConfigurationSection entry = section.createSection(marker.name);
			entry.set("world", marker.world);
			entry.set("min", List.of(marker.minX, marker.minY, marker.minZ));
			entry.set("max", List.of(marker.maxX, marker.maxY, marker.maxZ));
			entry.set("group", marker.group);
			entry.set("customized", marker.customized);
			// 범위의 max와 설정 항목의 max가 같은 이름이므로, 설정값은 반드시 하위 구역에 따로 저장한다.
			marker.settings.save(entry.createSection("settings"));
		}
		try {
			yaml.save(file);
		} catch (IOException e) {
			logger.log(Level.SEVERE, "markers.yml을 저장하지 못했습니다.", e);
		}
	}

	BoostSettings defaults() {
		return defaults;
	}

	Set<Material> jumpBlocks() {
		return jumpBlocks;
	}

	Collection<Marker> all() {
		return markers.values();
	}

	Marker get(String name) {
		return markers.get(name);
	}

	void add(Marker marker) {
		markers.put(marker.name, marker);
	}

	void remove(String name) {
		markers.remove(name);
	}

	/** 해당 블록을 포함하는 마커를 찾는다. 여러 개가 겹치면 먼저 만든 마커가 우선한다. */
	Marker find(String world, int x, int y, int z) {
		for (Marker marker : markers.values()) {
			if (marker.contains(world, x, y, z)) {
				return marker;
			}
		}
		return null;
	}

	List<Marker> members(String group) {
		List<Marker> members = new ArrayList<>();
		for (Marker marker : markers.values()) {
			if (group.equals(marker.group)) {
				members.add(marker);
			}
		}
		return members;
	}

	Collection<String> groups() {
		TreeSet<String> groups = new TreeSet<>();
		for (Marker marker : markers.values()) {
			if (marker.group != null) {
				groups.add(marker.group);
			}
		}
		return groups;
	}
}
