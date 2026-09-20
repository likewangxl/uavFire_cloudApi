package com.yx.uavfire.common.model;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import java.sql.*;
/** Store official names, restore SDK-compatible values for existing internal dispatch code. */
public class OfficialModelTypeHandler extends BaseTypeHandler<String> {
    @Override public void setNonNullParameter(PreparedStatement s,int i,String value,JdbcType type)throws SQLException{s.setString(i,DeviceModelNames.full(value));}
    @Override public String getNullableResult(ResultSet rs,String c)throws SQLException{return DeviceModelNames.code(rs.getString(c));}
    @Override public String getNullableResult(ResultSet rs,int c)throws SQLException{return DeviceModelNames.code(rs.getString(c));}
    @Override public String getNullableResult(CallableStatement s,int c)throws SQLException{return DeviceModelNames.code(s.getString(c));}
}
