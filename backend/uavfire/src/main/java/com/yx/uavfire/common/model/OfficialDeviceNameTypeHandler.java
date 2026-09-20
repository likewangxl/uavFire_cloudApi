package com.yx.uavfire.common.model;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import java.sql.*;
public class OfficialDeviceNameTypeHandler extends BaseTypeHandler<String> {
    @Override public void setNonNullParameter(PreparedStatement s,int i,String value,JdbcType type)throws SQLException{s.setString(i,DeviceModelNames.full(value));}
    @Override public String getNullableResult(ResultSet rs,String c)throws SQLException{return DeviceModelNames.full(rs.getString(c));}
    @Override public String getNullableResult(ResultSet rs,int c)throws SQLException{return DeviceModelNames.full(rs.getString(c));}
    @Override public String getNullableResult(CallableStatement s,int c)throws SQLException{return DeviceModelNames.full(s.getString(c));}
}
