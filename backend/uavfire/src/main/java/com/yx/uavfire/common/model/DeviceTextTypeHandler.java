package com.yx.uavfire.common.model;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import java.sql.*;
/** Human labels are normalized at storage boundaries; identifiers and original files are excluded. */
public class DeviceTextTypeHandler extends BaseTypeHandler<String> {
    @Override public void setNonNullParameter(PreparedStatement s,int i,String value,JdbcType type)throws SQLException{s.setString(i,DeviceModelNames.text(value));}
    @Override public String getNullableResult(ResultSet rs,String c)throws SQLException{return DeviceModelNames.text(rs.getString(c));}
    @Override public String getNullableResult(ResultSet rs,int c)throws SQLException{return DeviceModelNames.text(rs.getString(c));}
    @Override public String getNullableResult(CallableStatement s,int c)throws SQLException{return DeviceModelNames.text(s.getString(c));}
}
