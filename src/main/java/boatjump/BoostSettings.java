package boatjump;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/** 보트에 적용하는 설정값 묶음. 마커마다 하나씩 가지며, 마커 밖에서는 기본값이 적용된다. */
final class BoostSettings {
	/** 숫자 항목 하나의 정의. random에 범위를 생략했을 때의 범위와, 입력할 수 있는 한계를 함께 가진다. */
	private record Spec(double initial, double randomMin, double randomMax, double lowest, double highest) { }

	private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

	static {
		// 마커에 들어설 때 속도에 곱하는 수(속도률). 1보다 작으면 느려진다.
		SPECS.put("multiplier", new Spec(2.0, 0.1, 2.0, 0.0, 10.0));
		// 속도률을 곱한 뒤에 따로 더하는 속도(블록/틱).
		SPECS.put("bonus", new Spec(0.0, 0.0, 0.5, 0.0, 5.0));
		// 푸른얼음 가속으로 도달할 수 있는 최고 속도(블록/틱). 바닐라 푸른얼음의 최고 속도는 약 3.6이다.
		// 틱당 10블록을 넘으면 서버가 "너무 빠르게 움직였다"고 판단하여 보트를 되돌린다.
		SPECS.put("max", new Spec(8.0, 1.0, 8.0, 0.1, 9.5));
		// 넘을 수 없는 벽에 부딪혔을 때 뒤로 튕기는 정도. 부딪힌 속도에 곱한다. 0이면 튕기지 않는다.
		SPECS.put("wallbounce", new Spec(0.5, 0.0, 1.0, 0.0, 3.0));
		// 탄성 계수. 바닥에 떨어졌을 때 떨어진 속도에 곱해서 다시 튀어 오른다. 0이면 튀지 않는다.
		SPECS.put("elasticity", new Spec(0.0, 0.0, 1.0, 0.0, 1.5));
		// 마커 안의 바닥을 슬라임처럼 만든다. 블록의 종류와 관계없이, 바닥에 닿으면 이 속도(블록/틱)로 위로 튕긴다. 0이면 튕기지 않는다.
		SPECS.put("bounce", new Spec(0.0, 0.2, 1.0, 0.0, 3.0));
		// 실제 슬라임 블록을 밟았을 때 위로 튕기는 속도(블록/틱). 마커 밖에서도 동작한다. 0이면 튕기지 않는다.
		SPECS.put("slime", new Spec(0.5, 0.2, 1.0, 0.0, 3.0));
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
		KOREAN.put("multiplier", "속도률");
		KOREAN.put("bonus", "추가속도");
		KOREAN.put("max", "최고속도");
		KOREAN.put("wallbounce", "벽튕김");
		KOREAN.put("elasticity", "탄성");
		KOREAN.put("bounce", "슬라임");
		KOREAN.put("slime", "슬라임블록");
	}

	/** 명령어를 입력할 때 항목에 마우스를 올리면 보여 주는 설명. */
	static final Map<String, String> DESCRIPTIONS = Map.of(
		"enabled", "마커의 속도 변화를 켤지 여부입니다. true 또는 false (초기값 true)",
		"interval", "마커 안에서 속도 변화를 다시 적용하는 간격(틱)입니다. 0이면 들어설 때 한 번만 적용합니다. (초기값 0)",
		"multiplier", "마커에 들어설 때 속도에 곱하는 배수입니다. 1보다 작으면 느려집니다. (초기값 2.0)",
		"bonus", "배수와 별도로 더하는 속도(블록/틱)입니다. (초기값 0)",
		"max", "속도 변화로 도달할 수 있는 최고 속도(블록/틱)입니다. (초기값 8.0, 최대 9.5)",
		"wallbounce", "넘을 수 없는 벽에 부딪혔을 때 뒤로 튕기는 정도입니다. 부딪힌 속도에 곱합니다. 0이면 튕기지 않습니다. (초기값 0.5)",
		"elasticity", "탄성 계수입니다. 바닥에 떨어졌을 때 떨어진 속도에 곱해서 다시 튀어 오릅니다. 0이면 튀지 않습니다. (초기값 0)",
		"bounce", "마커 안의 바닥을 슬라임처럼 만듭니다. 블록의 종류와 관계없이, 바닥에 닿으면 이 속도(블록/틱)로 위로 튕깁니다. 0이면 튕기지 않습니다. (초기값 0)",
		"slime", "실제 슬라임 블록을 밟았을 때 위로 튕기는 속도(블록/틱)입니다. 마커 밖에서도 동작합니다. 0이면 튕기지 않습니다. (초기값 0.5)");

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
