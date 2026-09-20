package com.yx.uavfire.flightreport;
import com.yx.uavfire.common.model.*;
import org.junit.jupiter.api.Test;
import java.sql.PreparedStatement;
import org.apache.ibatis.type.JdbcType;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class DeviceModelNamesTest {
    @Test void officialNamesAndSdkCodesRoundTrip(){assertEquals("DJI Matrice 4T",DeviceModelNames.full("M4T"));assertEquals("DJI FlyCart 100",DeviceModelNames.full("FC100"));assertEquals("M300",DeviceModelNames.code("DJI Matrice 300 RTK"));assertEquals("M3TD",DeviceModelNames.code("0-91-1"));}
    @Test void humanTextIsCanonicalAndSerialIdentifiersRemainIntact(){assertEquals("DJI FlyCart 100 投放 / DJI Matrice 300 RTK",DeviceModelNames.text("FC100 投放 / M300"));assertEquals("SN_M4T_0123",DeviceModelNames.text("SN_M4T_0123"));assertEquals("DJI Zenmuse H20T",DeviceModelNames.text("DJI Zenmuse H20T"));}
    @Test void databaseWriterStoresFullName()throws Exception{PreparedStatement s=mock(PreparedStatement.class);new OfficialModelTypeHandler().setNonNullParameter(s,1,"M4T",JdbcType.VARCHAR);verify(s).setString(1,"DJI Matrice 4T");}
}
