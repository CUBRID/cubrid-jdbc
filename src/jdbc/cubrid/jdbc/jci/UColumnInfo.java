/*
 * Copyright (C) 2008 Search Solution Corporation. All rights reserved by Search Solution. 
 *
 * Redistribution and use in source and binary forms, with or without modification, 
 * are permitted provided that the following conditions are met: 
 *
 * - Redistributions of source code must retain the above copyright notice, 
 *   this list of conditions and the following disclaimer. 
 *
 * - Redistributions in binary form must reproduce the above copyright notice, 
 *   this list of conditions and the following disclaimer in the documentation 
 *   and/or other materials provided with the distribution. 
 *
 * - Neither the name of the <ORGANIZATION> nor the names of its contributors 
 *   may be used to endorse or promote products derived from this software without 
 *   specific prior written permission. 
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND 
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED 
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. 
 * IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, 
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, 
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, 
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, 
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) 
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY 
 * OF SUCH DAMAGE. 
 *
 */

/**
 * Title:        CUBRID Java Client Interface<p>
 * Description:  CUBRID Java Client Interface<p>
 * @version 2.0
 */

package @CUBRID_JCI@;

import @CUBRID_SQL@.CUBRIDOID;

/*
 * resultset�� ���� statement(execute(SQL query), getSchemaInfo, getByOid�� ����
 * ����Ǿ����� statements)���� �����Ͽ��� �� ������� �� column ������ �����ϰ�
 * �ִ� class�̴�.
 * normal statement�� �������� �� ������� column ������ flag isNullable����
 * column�� ���� class Name������ ������ �ִ�. getSchemaInfo�� getByOid�� ����
 * ������� column ������ ���� isNullable, getClassName�� call�� ������ error��
 * set�ȴ�.
 *
 * since 1.0
 */

public class UColumnInfo
{
  private final static byte GLO_INSTANCE_FALSE = 0, GLO_INSTANCE_TRUE = 1,
      GLO_INSTANCE_UNKNOWN = -1;

  private byte type;
  private byte collectionBaseType;
  private short scale;
  private int precision;
  private String name, className, attributeName;
  // private String FQDN;
  private boolean isNullable;
  private byte glo_instance_flag;
  
  private String defaultValue;
  private byte is_auto_increment;
  private byte is_unique_key;
  private byte is_primary_key;
  private byte is_foreign_key;
  private byte is_reverse_index;
  private byte is_reverse_unique;
  private byte is_shared;

  UColumnInfo(byte cType, short cScale, int cPrecision, String cName)
  {
    byte realType[];

    realType = UColumnInfo.confirmType(cType);
    type = realType[0];
    collectionBaseType = realType[1];
    scale = cScale;
    precision = cPrecision;
    name = cName;
    className = null;
    attributeName = null;
    isNullable = false;
    // FQDN = UColumnInfo.findFQDN(type, precision, collectionBaseType);
    glo_instance_flag = GLO_INSTANCE_UNKNOWN;

    defaultValue = null;
    is_auto_increment = 0;
    is_unique_key = 0;
    is_primary_key = 0;
    is_foreign_key = 0;
    is_reverse_index = 0;
    is_reverse_unique = 0;
    is_shared = 0;
  }
  
  /* get functions */
  public String getDefaultValue()
  {
    return defaultValue;
  }
  public byte getIsAutoIncrement()
  {
    return is_auto_increment;
  }
  public byte getIsUniqueKey()
  {
    return is_unique_key;
  }
  public byte getIsPrimaryKey()
  {
    return is_primary_key;
  }
  public byte getIsForeignKey()
  {
    return is_foreign_key;
  }
  public byte getIsReverseIndex()
  {
    return is_reverse_index;
  }
  public byte getIsReverseUnique()
  {
    return is_reverse_unique;
  }
  public byte getIsShared()
  {
    return is_shared;
  }

  /*
   * ���� Column�� nullable���� �ƴ����� ���� flag�� return���ش�.
   */

  public boolean isNullable()
  {
    return isNullable;
  }

  /*
   * ���� column�� ���� class name�� return�� �ش�.
   */

  public String getClassName()
  {
    return className;
  }

  /*
   * collection type�� ��� JDBC Driver�� �� element�� type�� ��� ���� ��쿡
   * ���� �� �ִ�. �� �� collection�� base type�� Ȯ���Ͽ� �˷��ִ� method�̴�.
   */

  public int getCollectionBaseType()
  {
    return collectionBaseType;
  }

  /*
   * column name�� return�� �ش�.
   */

  public String getColumnName()
  {
    return name;
  }

  /*
   * column precision�� return�� �ش�.
   */

  public int getColumnPrecision()
  {
    return precision;
  }

  /*
   * column scale�� return�� �ش�.
   */

  public int getColumnScale()
  {
    return (int) scale;
  }

  /*
   * column type�� return�� �ش�. �� �� return�Ǿ����� type�� class UUType��
   * ���ǵ� CUBRID Type�̴�.
   */

  public byte getColumnType()
  {
    return type;
  }

  /*
   * column value�� Driver�� ���� end-user���� �������� value�� FQDN (Fully
   * Qulified Domain Name)�� return�Ѵ�.
   */

  public String getFQDN()
  {
    // return FQDN;
    return (UColumnInfo.findFQDN(type, precision, collectionBaseType));
  }

  public String getRealColumnName()
  {
    return attributeName;
  }

  /*
   * collection type�� ��� server�ʿ��� �Ѿ���� type������ collection
   * type������ collection base type������ logical or�� ���յǾ� �Ѿ�´�. ��
   * method�� server�����κ��� �Ѿ�� type������ �а� collection type������
   * collection base type������ ���� �����Ͽ� array�� �Ѱ��ش�. (index 0 :
   * collection type, index 1 : collection base type)
   */

  static byte[] confirmType(byte originalType)
  {
    int collectionTypeOrNot = 0;
    byte typeInfo[];

    typeInfo = new byte[2];
    collectionTypeOrNot = originalType & (byte) 0140;
    switch (collectionTypeOrNot)
    {
    case 0:
      typeInfo[0] = originalType;
      typeInfo[1] = -1;
      return typeInfo;
    case 040:
      typeInfo[0] = UUType.U_TYPE_SET;
      typeInfo[1] = (byte) (originalType & 037);
      return typeInfo;
    case 0100:
      typeInfo[0] = UUType.U_TYPE_MULTISET;
      typeInfo[1] = (byte) (originalType & 037);
      return typeInfo;
    case 0140:
      typeInfo[0] = UUType.U_TYPE_SEQUENCE;
      typeInfo[1] = (byte) (originalType & 037);
      return typeInfo;
    default:
      typeInfo[0] = UUType.U_TYPE_NULL;
      typeInfo[1] = -1;
    }
    return typeInfo;
  }

  /*
   * normal statement�� ��� flag isNullable�� column�� ���� class name ����
   * ���� set�Ѵ�.
   */

  synchronized void setRemainedData(String aName, String cName, boolean hNull)
  {
    attributeName = aName;
    className = cName;
    isNullable = hNull;
  }
  
  /* set the extra fields */
  synchronized void setExtraData(String defValue, byte bAI, byte bUK, byte bPK, byte bFK, byte bRI, byte bRU, byte sh)
  {
    defaultValue = defValue;
    is_auto_increment = bAI;
    is_unique_key = bUK;
    is_primary_key = bPK;
    is_foreign_key = bFK;
    is_reverse_index = bRI;
    is_reverse_unique = bRU;
    is_shared = sh;
  }

  boolean isGloInstance(UConnection u_con, CUBRIDOID oid)
  {
    if (glo_instance_flag == GLO_INSTANCE_UNKNOWN)
    {
      Object obj;
      synchronized (u_con)
      {
        obj = u_con.oidCmd(oid, UConnection.IS_GLO_INSTANCE);
      }
      if (obj == null)
        glo_instance_flag = GLO_INSTANCE_FALSE;
      else
        glo_instance_flag = GLO_INSTANCE_TRUE;
    }

    if (glo_instance_flag == GLO_INSTANCE_TRUE)
      return true;

    return false;
  }

  /*
   * �־��� type�� precision, collection base type������ ������ end-user����
   * �������� column value�� FQDN(Fully Qulified Domain Name)�� �߰��Ѵ�.
   */

  private static String findFQDN(byte cType, int cPrecision, byte cBaseType)
  {
    switch (cType)
    {
    case UUType.U_TYPE_NULL:
      return "null";
    case UUType.U_TYPE_BIT:
      return (cPrecision == 8) ? "java.lang.Boolean" : "byte[]";
    case UUType.U_TYPE_VARBIT:
      return "byte[]";
    case UUType.U_TYPE_CHAR:
    case UUType.U_TYPE_NCHAR:
    case UUType.U_TYPE_VARCHAR:
    case UUType.U_TYPE_VARNCHAR:
      return "java.lang.String";
    case UUType.U_TYPE_NUMERIC:
      return "java.lang.Double";
    case UUType.U_TYPE_SHORT:
      return "java.lang.Short";
    case UUType.U_TYPE_INT:
      return "java.lang.Integer";
    case UUType.U_TYPE_BIGINT:
      return "java.lang.Long";
    case UUType.U_TYPE_FLOAT:
      return "java.lang.Float";
    case UUType.U_TYPE_MONETARY:
    case UUType.U_TYPE_DOUBLE:
      return "java.lang.Double";
    case UUType.U_TYPE_DATE:
      return "java.sql.Date";
    case UUType.U_TYPE_TIME:
      return "java.sql.Time";
    case UUType.U_TYPE_TIMESTAMP:
    case UUType.U_TYPE_DATETIME:
      return "java.sql.Timestamp";
    case UUType.U_TYPE_SET:
    case UUType.U_TYPE_SEQUENCE:
    case UUType.U_TYPE_MULTISET:
      break;
    case UUType.U_TYPE_OBJECT:
      return "@CUBRID_SQL@.CUBRIDOID";
    default:
      return "";
    }
    switch (cBaseType)
    {
    case UUType.U_TYPE_NULL:
      return "null";
    case UUType.U_TYPE_BIT:
      return (cPrecision == 8) ? "java.lang.Boolean[]" : "byte[][]";
    case UUType.U_TYPE_VARBIT:
      return "byte[][]";
    case UUType.U_TYPE_CHAR:
    case UUType.U_TYPE_NCHAR:
    case UUType.U_TYPE_VARCHAR:
    case UUType.U_TYPE_VARNCHAR:
      return "java.lang.String[]";
    case UUType.U_TYPE_NUMERIC:
      return "java.lang.Double[]";
    case UUType.U_TYPE_SHORT:
      return "java.lang.Short[]";
    case UUType.U_TYPE_INT:
      return "java.lang.Integer[]";
    case UUType.U_TYPE_BIGINT:
      return "java.lang.Long[]";
    case UUType.U_TYPE_FLOAT:
      return "java.lang.Float[]";
    case UUType.U_TYPE_MONETARY:
    case UUType.U_TYPE_DOUBLE:
      return "java.lang.Double[]";
    case UUType.U_TYPE_DATE:
      return "java.sql.Date[]";
    case UUType.U_TYPE_TIME:
      return "java.sql.Time[]";
    case UUType.U_TYPE_TIMESTAMP:
    case UUType.U_TYPE_DATETIME:
      return "java.sql.Timestamp[]";
    case UUType.U_TYPE_SET:
    case UUType.U_TYPE_SEQUENCE:
    case UUType.U_TYPE_MULTISET:
      break;
    case UUType.U_TYPE_OBJECT:
      return "@CUBRID_SQL@.CUBRIDOID[]";
    default:
      break;
    }
    return null;
  }
}
