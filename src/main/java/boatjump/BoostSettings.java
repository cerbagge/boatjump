package boatjump;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/** 보트에 적용하는 설정값 묶음. 마커마다 하나씩 가지며, 마커 밖에서는 기본값이 적용된다. */
final class BoostSettings {
	/** 숫자 항목 하나의 정의. random에 범위를 생략했을 때의 범위와, 입력할 수 있는 한계를 함께 가진다. */
	private record Spec(double initial, double randomMin, double randomMax, double lowest, double highest) { }

	/** 숫자 항목에 넣을 수 있는 가장 큰 값. */
	private static final double MAX_VALUE = 100.0;
	/**
	 * 서버가 한 틱에 허용하는 이동 거리(블록)에 조금 못 미치는 값.
	 * 보트가 이보다 빠르게 움직이면 서버가 "너무 빠르게 움직였다"고 판단하여 보트를 되돌려 놓는다.
	 * 이 한계는 서버의 spigot.yml(moved-too-quickly-multiplier)에서만 바꿀 수 있다.
	 */
	static final double SERVER_SPEED_LIMIT = 9.5;

	private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

	static {
		// 마커에 들어설 때 보트의 속도에 더하는 값(블록/초). 음수이면 느려진다. 0이면 속도를 바꾸지 않는다.
		SPECS.put("speed", new Spec(0.0, -20.0, 20.0, -MAX_VALUE, MAX_VALUE));
		// 보트가 한 번에 오를 수 있는 높이(칸). 0이면 오르지 않고, 1이면 한 칸, 2 이상이면 그 높이까지의 턱을 오른다. 소수는 반올림한다.
		SPECS.put("step", new Spec(1.0, 1.0, 3.0, 0.0, MAX_VALUE));
		// 탄성 계수. 바닥에 떨어졌을 때에는 떨어진 속도에, 벽에 부딪혔을 때에는 부딪힌 속도에 곱해서 되튄다. 0이면 튀지 않는다.
		SPECS.put("elasticity", new Spec(0.0, 0.0, 1.0, 0.0, MAX_VALUE));
		// 바닥에 닿으면 이 속도(블록/틱)로 위로 튕긴다. 마커 안에서는 블록의 종류와 관계없이 튕기고, 마커 밖에서는 슬라임 블록 위에서만 튕긴다.
		// 0이면 마커 안의 바닥은 튕기지 않고, 슬라임 블록은 기본 세기로 튕긴다.
		SPECS.put("bounce", new Spec(0.0, 0.2, 1.0, 0.0, MAX_VALUE));
	}

	static final List<String> KEYS;

	static {
		List<String> keys = new java.util.ArrayList<>(List.of("enabled", "interval"));
		keys.addAll(SPECS.keySet());
		KEYS = List.copyOf(keys);
	}

	/** 설정 항목의 한국어 이름. 영어 이름에서 한국어 이름으로. */
	static final Map<String, String> KOREAN = new LinkedHashMap<>();

	static {
		KOREAN.put("enabled", "사용");
		KOREAN.put("interval", "간격");
		KOREAN.put("speed", "속도");
		KOREAN.put("step", "계단");
		KOREAN.put("elasticity", "탄성");
		KOREAN.put("bounce", "점프");
	}

	/** 명령어를 입력할 때 항목에 마우스를 올리면 보여 주는 설명. */
	static final Map<String, String> DESCRIPTIONS = Map.of(
		"enabled", "마커의 속도 변화를 켤지 여부입니다. 켜기 또는 끄기 (초기값 켜기)",
		"interval", "마커 안에서 속도 변화를 다시 적용하는 간격(틱)입니다. 0이면 들어설 때 한 번만 적용합니다. (초기값 0)",
		"speed", "마커에 들어설 때 보트의 속도에 더하는 값(블록/초)입니다. 20이면 초당 20블록 빨라지고, -20이면 그만큼 느려집니다. "
			+ "0이면 속도를 바꾸지 않습니다. (초기값 0, -100~100)",
		"step", "보트가 한 번에 오를 수 있는 높이(칸)입니다. 0이면 오르지 않고, 1이면 한 칸, 2 이상이면 그 높이까지의 턱을 오릅니다. (초기값 1)",
		"elasticity", "탄성 계수입니다. 바닥에 떨어졌을 때에는 떨어진 속도에, 넘을 수 없는 벽에 부딪혔을 때에는 부딪힌 속도에 곱해서 되튑니다. "
			+ "0이면 튀지 않습니다. (초기값 0)",
		"bounce", "바닥에 닿으면 이 속도(블록/틱)로 위로 튕깁니다. 마커 안에서는 블록의 종류와 관계없이 튕기고, 마커 밖에서는 슬라임 블록 위에서만 튕깁니다. "
			+ "0이면 마커 안의 바닥은 튕기지 않고, 슬라임 블록은 기본 세기(0.5)로 튕깁니다. (초기값 0)");

	/** 마커의 속도 변화를 켤지 여부. */
	boolean enabled = true;
	/** 같은 구역 안에서 다시 가속하기까지의 간격(틱). 0이면 구역에 들어설 때 한 번만 가속한다. */
	int interval = 0;
	private final Map<String, Value> values = new LinkedHashMap<>();

	BoostSettings() {
		SPECS.forEach((key, spec) -> values.put(key, Value.fixed(spec.initial())));
	}

	BoostSettings copy() {
		BoostSettings copy = new BoostSettings();
		copy.enabled = enabled;
		copy.interval = interval;
		copy.values.putAll(values);
		return copy;
	}

	/** 숫자 항목의 현재 값. 무작위 항목이면 현재 서버 틱에 해당하는 값을 돌려준다. */
	double get(String key, int tick) {
		return values.get(key).get(tick, key.hashCode());
	}

	/** 값을 고친다. 고칠 수 없으면 그 이유를 돌려주고, 성공하면 null을 돌려준다. */
	String set(String key, String[] tokens) {
		try {
			switch (key) {
				case "enabled" -> {
					if (tokens.length != 1 || !(tokens[0].equals("true") || tokens[0].equals("false"))) {
						return "사용 값은 켜기 또는 끄기여야 합니다.";
					}
					enabled = Boolean.parseBoolean(tokens[0]);
				}
				case "interval" -> {
					Value parsed = Value.parse(tokens, 0, 0, 0, 1200);
					if (parsed.period != 0) {
						return "간격에는 무작위를 쓸 수 없습니다.";
					}
					interval = (int) parsed.min;
				}
				default -> {
					Spec spec = SPECS.get(key);
					if (spec == null) {
						return "알 수 없는 항목입니다. 사용할 수 있는 항목: " + String.join(", ", KOREAN.values());
					}
					values.put(key, Value.parse(tokens, spec.randomMin(), spec.randomMax(), spec.lowest(), spec.highest()));
				}
			}
			return null;
		} catch (IllegalArgumentException e) {
			return e.getMessage();
		}
	}

	/**
	 * 방금 고친 항목이 서버가 되돌릴 만큼 빠른 움직임을 만들 수 있으면 경고 문구를 돌려주고, 아니면 null을 돌려준다.
	 * 값 자체는 막지 않는다. 서버 설정을 바꾸어 한계를 올린 서버에서는 그대로 쓸 수 있기 때문이다.
	 */
	String speedWarning(String key) {
		if (!key.equals("bounce") || values.get(key).max <= SERVER_SPEED_LIMIT) {
			return null;
		}
		return "주의: 틱당 " + SERVER_SPEED_LIMIT + "블록을 넘는 속도는 서버가 \"너무 빠르게 움직였다\"고 판단하여 보트를 되돌려 놓을 수 있습니다. "
			+ "서버의 spigot.yml에서 moved-too-quickly-multiplier 값을 올리면 허용됩니다.";
	}

	/** 사람이 읽을 수 있도록 한국어로 적은 설정값. */
	String describe() {
		StringBuilder text = new StringBuilder("사용=" + (enabled ? "켜기" : "끄기") + ", 간격=" + interval);
		values.forEach((key, value) -> text.append(", ").append(KOREAN.get(key)).append('=').append(value.display()));
		return text.toString();
	}

	void save(ConfigurationSection section) {
		section.set("enabled", enabled);
		section.set("interval", interval);
		values.forEach((key, value) -> section.set(key, value.toString()));
	}

	static BoostSettings load(ConfigurationSection section) {
		BoostSettings settings = new BoostSettings();
		if (section == null) {
			return settings;
		}
		settings.enabled = section.getBoolean("enabled", settings.enabled);
		settings.interval = section.getInt("interval", settings.interval);
		for (String key : SPECS.keySet()) {
			String text = section.getString(key);
			if (text != null) {
				// 파일의 값이 잘못되어 있으면 그 항목만 초기값으로 둔다.
				settings.set(key, text.trim().split("\\s+"));
			}
		}
		return settings;
	}
}
