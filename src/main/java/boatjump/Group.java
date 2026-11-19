package boatjump;

/** 여러 마커가 설정값을 함께 쓰도록 묶는 그룹. 마커가 하나도 없어도 존재할 수 있다. */
final class Group {
	final String name;
	BoostSettings settings;
	/** 설정 명령어로 설정값을 고친 적이 있는지 여부. 범위를 표시할 때의 색을 정한다. */
	boolean customized;

	Group(String name, BoostSettings settings) {
		this.name = name;
		this.settings = settings;
	}
}
