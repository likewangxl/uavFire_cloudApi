package com.yx.uavfire.fc100.route.builder;

import com.yx.uavfire.fc100.route.config.Fc100RouteProperties;
import com.yx.uavfire.fc100.waypoint.model.dto.MissionWaypointDTO;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayOutputStream;

/**
 * 基于 samples/wpml-schema-notes.md 实测的 WPML 1.0.6 schema 生成 template.kml 与 waylines.wpml。
 *
 * <p>关键约定：
 * <ul>
 *   <li>命名空间 1.0.6（与 Pilot 2 实测一致）</li>
 *   <li>coordinates 只放 lng,lat（2D）；高度在 wpml:ellipsoidHeight + wpml:height 分别字段</li>
 *   <li>FC100 cargo 任务不放 actionGroup（WPML 官方枚举无投放动作）</li>
 *   <li>height = ellipsoidHeight + egm96Offset（线性 fallback，二期接 DEM）</li>
 * </ul>
 */
@Component
public class Fc100WpmlBuilder {

    public static final String NS_KML = "http://www.opengis.net/kml/2.2";

    private final Fc100RouteProperties props;

    public Fc100WpmlBuilder(Fc100RouteProperties props) {
        this.props = props;
    }

    private String wpmlNs() {
        return "http://www.dji.com/wpmz/" + props.getWpmlVersion();
    }

    /** template.kml — 设计模板 */
    public byte[] buildTemplateKml(WpmlBuildContext ctx) {
        return writeXml(w -> {
            w.writeStartDocument("UTF-8", "1.0");
            w.writeStartElement("kml");
            w.writeDefaultNamespace(NS_KML);
            w.writeNamespace("wpml", wpmlNs());

            w.writeStartElement("Document");

            elem(w, wpmlNs(), "createTime", String.valueOf(ctx.getCreateTimeMs()));
            elem(w, wpmlNs(), "updateTime", String.valueOf(ctx.getCreateTimeMs()));

            // missionConfig
            w.writeStartElement(wpmlNs(), "missionConfig");
            elem(w, wpmlNs(), "flyToWaylineMode", "safely");
            elem(w, wpmlNs(), "finishAction", "goHome");
            elem(w, wpmlNs(), "exitOnRCLost", "executeLostAction");
            elem(w, wpmlNs(), "executeRCLostAction", "goBack");
            int safetyH = orDefault(ctx.getTakeOffSecurityHeight(), props.getTakeOffSecurityHeight());
            elem(w, wpmlNs(), "takeOffSecurityHeight", String.valueOf(safetyH));
            if (ctx.getTakeOffLat() != null) {
                // takeOffRefPoint 格式: lat,lng,alt
                elem(w, wpmlNs(), "takeOffRefPoint",
                    ctx.getTakeOffLat() + "," + ctx.getTakeOffLng() + "," + nz(ctx.getTakeOffAlt()));
            }
            int gSpeed = (ctx.getGlobalSpeed() != null
                ? ctx.getGlobalSpeed().intValue()
                : props.getGlobalTransitionalSpeed());
            elem(w, wpmlNs(), "globalTransitionalSpeed", String.valueOf(gSpeed));

            w.writeStartElement(wpmlNs(), "droneInfo");
            elem(w, wpmlNs(), "droneEnumValue",
                String.valueOf(orDefault(ctx.getDroneEnumValue(), props.getDefaultDroneEnumValue())));
            elem(w, wpmlNs(), "droneSubEnumValue",
                String.valueOf(orDefault(ctx.getDroneSubEnumValue(), props.getDefaultDroneSubEnumValue())));
            w.writeEndElement();

            elem(w, wpmlNs(), "waylineAvoidLimitAreaMode", "0");

            w.writeStartElement(wpmlNs(), "payloadInfo");
            elem(w, wpmlNs(), "payloadEnumValue",
                String.valueOf(orDefault(ctx.getPayloadEnumValue(), props.getDefaultPayloadEnumValue())));
            elem(w, wpmlNs(), "payloadSubEnumValue",
                String.valueOf(orDefault(ctx.getPayloadSubEnumValue(), props.getDefaultPayloadSubEnumValue())));
            elem(w, wpmlNs(), "payloadPositionIndex", "0");
            w.writeEndElement();

            w.writeEndElement(); // /missionConfig

            // Folder
            w.writeStartElement("Folder");
            elem(w, wpmlNs(), "templateType", "waypoint");
            elem(w, wpmlNs(), "templateId", "0");

            w.writeStartElement(wpmlNs(), "waylineCoordinateSysParam");
            elem(w, wpmlNs(), "coordinateMode", "WGS84");
            elem(w, wpmlNs(), "heightMode", "EGM96");
            elem(w, wpmlNs(), "positioningType", "Custom");
            w.writeEndElement();

            elem(w, wpmlNs(), "autoFlightSpeed", String.valueOf(gSpeed));
            elem(w, wpmlNs(), "globalHeight",
                String.valueOf(globalAvgHeight(ctx.getWaypoints())));
            elem(w, wpmlNs(), "caliFlightEnable", "0");
            elem(w, wpmlNs(), "gimbalPitchMode", "manual");

            w.writeStartElement(wpmlNs(), "globalWaypointHeadingParam");
            elem(w, wpmlNs(), "waypointHeadingMode", "followWayline");
            elem(w, wpmlNs(), "waypointHeadingAngle", "0");
            elem(w, wpmlNs(), "waypointPoiPoint", "0.000000,0.000000,0.000000");
            elem(w, wpmlNs(), "waypointHeadingPoiIndex", "0");
            w.writeEndElement();

            elem(w, wpmlNs(), "globalWaypointTurnMode", "toPointAndStopWithDiscontinuityCurvature");
            elem(w, wpmlNs(), "globalUseStraightLine", "0");

            // 每个航点 Placemark
            for (MissionWaypointDTO wp : ctx.getWaypoints()) {
                writeTemplatePlacemark(w, wp);
            }

            w.writeEndElement(); // /Folder
            w.writeEndElement(); // /Document
            w.writeEndElement(); // /kml
            w.writeEndDocument();
        });
    }

    /** waylines.wpml — 可执行航线（与 template 几乎相同，多 executeHeightMode/waylineId/distance/duration） */
    public byte[] buildWaylinesWpml(WpmlBuildContext ctx) {
        return writeXml(w -> {
            w.writeStartDocument("UTF-8", "1.0");
            w.writeStartElement("kml");
            w.writeDefaultNamespace(NS_KML);
            w.writeNamespace("wpml", wpmlNs());
            w.writeStartElement("Document");

            // missionConfig（同 template）
            w.writeStartElement(wpmlNs(), "missionConfig");
            elem(w, wpmlNs(), "flyToWaylineMode", "safely");
            elem(w, wpmlNs(), "finishAction", "goHome");
            elem(w, wpmlNs(), "exitOnRCLost", "executeLostAction");
            elem(w, wpmlNs(), "executeRCLostAction", "goBack");
            int safetyH = orDefault(ctx.getTakeOffSecurityHeight(), props.getTakeOffSecurityHeight());
            elem(w, wpmlNs(), "takeOffSecurityHeight", String.valueOf(safetyH));
            int gSpeed = (ctx.getGlobalSpeed() != null
                ? ctx.getGlobalSpeed().intValue()
                : props.getGlobalTransitionalSpeed());
            elem(w, wpmlNs(), "globalTransitionalSpeed", String.valueOf(gSpeed));
            w.writeStartElement(wpmlNs(), "droneInfo");
            elem(w, wpmlNs(), "droneEnumValue",
                String.valueOf(orDefault(ctx.getDroneEnumValue(), props.getDefaultDroneEnumValue())));
            elem(w, wpmlNs(), "droneSubEnumValue",
                String.valueOf(orDefault(ctx.getDroneSubEnumValue(), props.getDefaultDroneSubEnumValue())));
            w.writeEndElement();
            elem(w, wpmlNs(), "waylineAvoidLimitAreaMode", "0");
            w.writeStartElement(wpmlNs(), "payloadInfo");
            elem(w, wpmlNs(), "payloadEnumValue",
                String.valueOf(orDefault(ctx.getPayloadEnumValue(), props.getDefaultPayloadEnumValue())));
            elem(w, wpmlNs(), "payloadSubEnumValue",
                String.valueOf(orDefault(ctx.getPayloadSubEnumValue(), props.getDefaultPayloadSubEnumValue())));
            elem(w, wpmlNs(), "payloadPositionIndex", "0");
            w.writeEndElement();
            w.writeEndElement(); // /missionConfig

            // Folder
            w.writeStartElement("Folder");
            elem(w, wpmlNs(), "templateId", "0");
            elem(w, wpmlNs(), "executeHeightMode", "WGS84");
            elem(w, wpmlNs(), "waylineId", "0");

            double distance = totalDistance(ctx.getWaypoints());
            elem(w, wpmlNs(), "distance", String.valueOf(distance));
            elem(w, wpmlNs(), "duration", String.valueOf(distance / Math.max(gSpeed, 1)));
            elem(w, wpmlNs(), "autoFlightSpeed", String.valueOf(gSpeed));

            for (MissionWaypointDTO wp : ctx.getWaypoints()) {
                writeWaylinePlacemark(w, wp, gSpeed);
            }

            w.writeEndElement(); // /Folder
            w.writeEndElement(); // /Document
            w.writeEndElement(); // /kml
            w.writeEndDocument();
        });
    }

    // ============= Placemark builders =============

    private void writeTemplatePlacemark(XMLStreamWriter w, MissionWaypointDTO wp) throws Exception {
        double ellipsoid = wp.getAlt();
        double height = ellipsoid + props.getEgm96OffsetMeters();
        w.writeStartElement("Placemark");
        w.writeStartElement("Point");
        elem(w, NS_KML, "coordinates", wp.getLng() + "," + wp.getLat());
        w.writeEndElement();
        elem(w, wpmlNs(), "index", wp.getWaypointIndex().toString());
        elem(w, wpmlNs(), "ellipsoidHeight", String.valueOf(ellipsoid));
        elem(w, wpmlNs(), "height", String.valueOf(height));
        w.writeStartElement(wpmlNs(), "waypointTurnParam");
        elem(w, wpmlNs(), "waypointTurnMode", "toPointAndPassWithContinuityCurvature");
        elem(w, wpmlNs(), "waypointTurnDampingDist", "0");
        w.writeEndElement();
        elem(w, wpmlNs(), "useGlobalSpeed", "1");
        elem(w, wpmlNs(), "useGlobalHeadingParam", "1");
        elem(w, wpmlNs(), "useStraightLine", "1");
        elem(w, wpmlNs(), "isRisky", "0");
        w.writeEndElement();
    }

    private void writeWaylinePlacemark(XMLStreamWriter w, MissionWaypointDTO wp, int gSpeed) throws Exception {
        double ellipsoid = wp.getAlt();
        w.writeStartElement("Placemark");
        w.writeStartElement("Point");
        elem(w, NS_KML, "coordinates", wp.getLng() + "," + wp.getLat());
        w.writeEndElement();
        elem(w, wpmlNs(), "index", wp.getWaypointIndex().toString());
        elem(w, wpmlNs(), "executeHeight", String.valueOf(ellipsoid));
        elem(w, wpmlNs(), "waypointSpeed",
            String.valueOf(wp.getSpeed() != null ? wp.getSpeed().intValue() : gSpeed));
        w.writeStartElement(wpmlNs(), "waypointHeadingParam");
        elem(w, wpmlNs(), "waypointHeadingMode", "followWayline");
        elem(w, wpmlNs(), "waypointHeadingAngle", "0");
        elem(w, wpmlNs(), "waypointPoiPoint", "0.000000,0.000000,0.000000");
        elem(w, wpmlNs(), "waypointHeadingAngleEnable", "0");
        elem(w, wpmlNs(), "waypointHeadingPoiIndex", "0");
        w.writeEndElement();
        w.writeStartElement(wpmlNs(), "waypointTurnParam");
        elem(w, wpmlNs(), "waypointTurnMode", "toPointAndStopWithDiscontinuityCurvature");
        elem(w, wpmlNs(), "waypointTurnDampingDist", "0");
        w.writeEndElement();
        elem(w, wpmlNs(), "useStraightLine", "1");
        w.writeStartElement(wpmlNs(), "waypointGimbalHeadingParam");
        elem(w, wpmlNs(), "waypointGimbalPitchAngle", "0");
        elem(w, wpmlNs(), "waypointGimbalYawAngle", "0");
        w.writeEndElement();
        elem(w, wpmlNs(), "isRisky", "0");
        elem(w, wpmlNs(), "waypointWorkType", "0");
        w.writeEndElement();
    }

    // ============= helpers =============

    private static void elem(XMLStreamWriter w, String ns, String name, String value) throws Exception {
        w.writeStartElement(ns, name);
        w.writeCharacters(value);
        w.writeEndElement();
    }

    private static int orDefault(Integer v, int def) {
        return v == null ? def : v;
    }

    private static String nz(Double d) {
        return d == null ? "0" : d.toString();
    }

    private static double totalDistance(java.util.List<MissionWaypointDTO> wps) {
        double t = 0;
        for (int i = 1; i < wps.size(); i++) {
            t += com.yx.uavfire.fc100.common.GeoUtils.distance(
                wps.get(i - 1).getLat(), wps.get(i - 1).getLng(),
                wps.get(i).getLat(), wps.get(i).getLng());
        }
        return t;
    }

    private static int globalAvgHeight(java.util.List<MissionWaypointDTO> wps) {
        return (int) wps.stream().mapToDouble(MissionWaypointDTO::getAlt).average().orElse(100);
    }

    @FunctionalInterface
    private interface XmlWriter {
        void write(XMLStreamWriter w) throws Exception;
    }

    private static byte[] writeXml(XmlWriter writer) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            XMLStreamWriter w = XMLOutputFactory.newInstance().createXMLStreamWriter(bos, "UTF-8");
            writer.write(w);
            w.flush();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("wpml build failed", e);
        }
    }
}
