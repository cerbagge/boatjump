package boatjump;

/** 푸른얼음 부스터의 설정값을 따로 지정하는 직육면체 구역. 좌표는 블록 단위이며 양 끝을 포함한다. */
final class Marker {
	final String name;
	final String world;
	final int minX;
	final int minY;
	final int minZ;
	final int maxX;
	final int maxY;
	final int maxZ;
	/** 속한 그룹의 이름. 그룹이 없으면 null이다. 같은 그룹의 마커는 설정값을 함께 고친다. */
	String group;
	BoostSettings settings;
	/** set 명령어로 설정값을 고친 적이 있는지 여부. 범위를 표시할 때의 색을 정한다. */
	boolean customized;

	Marker(String name, String world, int x1, int y1, int z1, int x2, int y2, int z2, String group, BoostSettings settings) {
		this.name = name;
		this.world = world;
		this.minX = Math.min(x1, x2);
		this.minY = Math.min(y1, y2);
		this.minZ = Math.min(z1, z2);
		this.maxX = Math.max(x1, x2);
		this.maxY = Math.max(y1, y2);
		this.maxZ = Math.max(z1, z2);
		this.group = group;
		this.settings = settings;
	}

	boolean contains(String world, int x, int y, int z) {
		return this.world.equals(world)
			&& x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
	}
}
