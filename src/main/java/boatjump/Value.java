package boatjump;

import java.util.SplittableRandom;

/**
 * 설정값 하나. 고정된 숫자이거나, 정해진 틱마다 범위 안에서 무작위로 바뀌는 숫자이다.
 * 무작위 값은 서버 틱으로 정해지므로, 같은 시각에는 모든 보트가 같은 값을 본다.
 */
final class Value {
	/** random에 틱을 지정하지 않았을 때 값이 바뀌는 간격(틱). */
	static final int DEFAULT_PERIOD = 4;

	final double min;
	final double max;
	/** 값이 바뀌는 간격(틱). 0이면 고정값이며, 이때 min과 max는 같다. */
	final int period;

	private Value(double min, double max, int period) {
		this.min = min;
		this.max = max;
		this.period = period;
	}

	static Value fixed(double value) {
		return new Value(value, value, 0);
	}

	/**
	 * 명령어나 파일의 표기를 읽는다. 다음 형식을 받는다.
	 * "2.0" / "random" / "random 틱" / "random 최소 최대" / "random 최소 최대 틱"
	 * 범위를 생략하면 randomMin~randomMax를 쓰고, 틱을 생략하면 4틱을 쓴다.
	 * 값은 lowest 이상 highest 이하여야 한다.
	 */
	static Value parse(String[] tokens, double randomMin, double randomMax, double lowest, double highest) {
		if (tokens.length == 0) {
			throw new IllegalArgumentException("값을 입력해야 합니다.");
		}
		if (!tokens[0].equalsIgnoreCase("random")) {
			if (tokens.length != 1) {
				throw new IllegalArgumentException("고정값은 숫자 하나만 입력해야 합니다.");
			}
			return fixed(inRange(number(tokens[0]), lowest, highest));
		}

		double min = randomMin;
		double max = randomMax;
		int period = DEFAULT_PERIOD;
		switch (tokens.length) {
			case 1 -> { }
			case 2 -> period = (int) number(tokens[1]);
			case 3, 4 -> {
				min = inRange(number(tokens[1]), lowest, highest);
				max = inRange(number(tokens[2]), lowest, highest);
				if (tokens.length == 4) {
					period = (int) number(tokens[3]);
				}
			}
			default -> throw new IllegalArgumentException("형식: random [최소 최대] [틱]");
		}
		if (min > max) {
			throw new IllegalArgumentException("최소값이 최대값보다 클 수 없습니다.");
		}
		if (period < 1 || period > 12000) {
			throw new IllegalArgumentException("틱은 1 이상 12000 이하여야 합니다.");
		}
		return new Value(min, max, period);
	}

	private static double number(String token) {
		try {
			return Double.parseDouble(token);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("숫자를 입력해야 합니다: " + token);
		}
	}

	private static double inRange(double value, double lowest, double highest) {
		if (!(value >= lowest && value <= highest)) {
			throw new IllegalArgumentException("값은 " + lowest + " 이상 " + highest + " 이하여야 합니다.");
		}
		return value;
	}

	/** 현재 서버 틱에서의 값. salt는 항목마다 다른 무작위 값이 나오도록 섞는 수이다. */
	double get(int tick, int salt) {
		if (period == 0 || min == max) {
			return min;
		}
		long bucket = tick / period;
		return new SplittableRandom(bucket * 0x9E3779B97F4A7C15L + salt).nextDouble(min, max);
	}

	@Override
	public String toString() {
		return period == 0 ? String.valueOf(min) : "random " + min + " " + max + " " + period;
	}
}
