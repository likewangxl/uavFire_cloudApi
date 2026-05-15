package com.yx.uavfire.fc100.common;

/**
 * 球面坐标偏移与 Haversine 距离工具。
 *
 * <p>spec §5.1：bearing 0=N，90=E，顺时针。坐标系 WGS84。
 */
public final class GeoUtils {

    /** WGS84 长半轴半径（米） */
    private static final double EARTH_RADIUS_M = 6378137.0;

    private GeoUtils() {}

    public static final class GeoPoint {
        private final double lat;
        private final double lng;
        public GeoPoint(double lat, double lng) { this.lat = lat; this.lng = lng; }
        public double lat() { return lat; }
        public double lng() { return lng; }
    }

    /**
     * 沿 bearingDeg 方向偏移 distanceMeters 米，返回新经纬度。
     * @param bearingDeg 0=正北，90=东，180=南，270=西
     */
    public static GeoPoint offset(double lat, double lng, double distanceMeters, double bearingDeg) {
        double brng = Math.toRadians(bearingDeg);
        double lat1 = Math.toRadians(lat);
        double lon1 = Math.toRadians(lng);
        double dr = distanceMeters / EARTH_RADIUS_M;

        double lat2 = Math.asin(Math.sin(lat1) * Math.cos(dr)
                + Math.cos(lat1) * Math.sin(dr) * Math.cos(brng));
        double lon2 = lon1 + Math.atan2(
                Math.sin(brng) * Math.sin(dr) * Math.cos(lat1),
                Math.cos(dr) - Math.sin(lat1) * Math.sin(lat2));

        return new GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2));
    }

    /** Haversine 球面距离（米）。 */
    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLam = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2)
                * Math.sin(dLam / 2) * Math.sin(dLam / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_M * c;
    }
}
