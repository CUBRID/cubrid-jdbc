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

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import @CUBRID_SQL@.CUBRIDOID;
import @CUBRID_SQL@.CUBRIDTimestamp;

/**
 * class Object�� instance�� parameter�� �޾� jdbc���� getXXX method�� ���� type
 * converting�� �����ϴٰ� ���ǵ� type���� parameter�� convert�Ͽ� return�Ͽ�
 * �ִ� class�̴�. ���� type conversion�� �Ұ����� object�� parameter�ν� �Ѿ��
 * ��� Exception�� throw�Ѵ�.
 * 
 * since 1.0
 */

abstract public class UGetTypeConvertedValue
{
  /*
   * parameter data�� java.math.BigDecimal type���� conversion�Ͽ� return�Ѵ�.
   * Conversion������ instance type BigDecimal, String, Byte, Boolean, Short,
   * Integer, Double, Float
   */

  static public BigDecimal getBigDecimal(Object data) throws UJciException
  {
    if (data == null)
      return null;
    else if (data instanceof BigDecimal)
      return (BigDecimal) data;
    else if (data instanceof String)
    {
      try
      {
        return new BigDecimal((String) data);
      }
      catch (NumberFormatException e)
      {
        throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
      }
    }
    else if (data instanceof Number)
      return new BigDecimal(((Number) data).doubleValue());
    else if (data instanceof Boolean)
      return new BigDecimal(
          (((Boolean) data).booleanValue() == true) ? (double) 1 : (double) 0);
    throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
  }

  /*
   * parameter data�� boolean type���� conversion�Ͽ� return�Ѵ�.
   * Conversion������ instance type Boolean, String, Byte, byte [], Short,
   * Integer, Double, Float, BigDecimal
   */

  static public boolean getBoolean(Object data) throws UJciException
  {
    if (data == null)
      return false;
    else if (data instanceof Boolean)
      return ((Boolean) data).booleanValue();
    else if (data instanceof String)
      return ((((String) data).compareTo("0") == 0) ? false : true);
    else if (data instanceof Number)
      return ((((Number) data).doubleValue() == (double) 0) ? false : true);
    else if (data instanceof byte[])
      return ((((byte[]) data)[0] == 0) ? false : true);
    throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
  }

  /*
   * parameter data�� byte type���� conversion�Ͽ� return�Ѵ�. Conversion������
   * instance type Byte, String, Boolean, Short, Integer, Double, Float,
   * BigDecimal
   */

  static public byte getByte(Object data) throws UJciException
  {
    if (data == null)
      return (byte) 0;
    else if (data instanceof Number)
      return ((Number) data).byteValue();
    else if (data instanceof byte[])
    {
      if (((byte[]) data).length != 1)
        throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
      return ((byte[]) data)[0];
    }
    else if (data instanceof String)
    {
      try
      {
        return Byte.parseByte((String) data);
      }
      catch (NumberFormatException e)
      {
        throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
      }
    }
    else if (data instanceof Boolean)
      return ((((Boolean) data).booleanValue() == true) ? (byte) -128
          : (byte) 0);
    throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
  }

  /*
   * parameter data�� byte [] type���� conversion�Ͽ� return�Ѵ�.
   * Conversion������ instance type byte[], String
   */

  static public byte[] getBytes(Object data) throws UJciException
  {
    if (data == null)
      return null;

    if (data instanceof byte[])
      return (byte[]) ((byte[]) data).clone();

    throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
  }

  /*
   * parameter data�� java.sql.Date type���� conversion�Ͽ� return�Ѵ�.
   * Conversion������ instance type Date, String, Timestamp
   */

  static public Date getDate(Object data) throws UJciException
  {
    if (data == null)
      return null;

    else if (data instanceof Date)
      return new Date(((Date) data).getTime());
    else if (data instanceof Timestamp)
      return new Date(((Timestamp) data).getTime());
    else if (data instanceof String)
    {
      try
      {
        return Date.valueOf((String) data);
      }
      catch (IllegalArgumentException e)
      {
        throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
      }
    }

    throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
  }

  /*
   * parameter data�� double type���� conversion�Ͽ� return�Ѵ�.
   * Conversion������ instance type Double, String, Byte, Boolean, Short,
   * Integer, Float, BigDecimal
   */

  static public double getDouble(Object data) throws UJciException
  {
    if (data == null)
      return (double) 0;
    else if (data instanceof Number)
      return ((Number) data).doubleValue();
    else if (data instanceof String)
    {
      try
      {
        return Double.parseDouble((String) data);
      }
      catch (NumberFormatException e)
      {
        throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
      }
    }
    else if (data instanceof Boolean)
      return ((((Boolean) data).booleanValue() == true) ? (double) 1
          : (double) 0);
    throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
  }

  /*
   * parameter data�� float type���� conversion�Ͽ� return�Ѵ�. Conversion������
   * instance type Float, String, Byte, Boolean, Short, Integer, Double,
   * BigDecimal
   */

  static public float getFloat(Object data) throws UJciException
  {
    if (data == null)
      return (float) 0;
    else if (data instanceof Number)
      return ((Number) data).floatValue();
    else if (data instanceof String)
    {
      try
      {
        return Float.parseFloat((String) data);
      }
      catch (NumberFormatException e)
      {
        throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
      }
    }
    else if (data instanceof Boolean)
      return ((((Boolean) data).booleanValue() == true) ? (float) 1 : (float) 0);
    throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
  }

  /*
   * parameter data�� int type���� conversion�Ͽ� return�Ѵ�. Conversion������
   * instance type Integer, String, Byte, Boolean, Short, Double, Float,
   * BigDecimal
   */

  static public int getInt(Object data) throws UJciException
  {
    if (data == null)
      return 0;
    else if (data instanceof Number)
      return ((Number) data).intValue();
    else if (data instanceof String)
    {
      try
      {
        return Integer.parseInt((String) data);
      }
      catch (NumberFormatException e)
      {
        throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
      }
    }
    else if (data instanceof Boolean)
      return ((((Boolean) data).booleanValue() == true) ? 1 : 0);
    throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
  }

  /*
   * parameter data�� long type���� conversion�Ͽ� return�Ѵ�. Conversion������
   * instance type String, Byte, Boolean, Short, Integer, Double, Float,
   * BigDecimal
   */

  static public long getLong(Object data) throws UJciException
  {
    if (data == null)
      return (long) 0;
    else if (data instanceof String)
    {
      try
      {
        return Long.parseLong((String) data);
      }
      catch (NumberFormatException e)
      {
        throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
      }
    }
    else if (data instanceof Number)
      return ((Number) data).longValue();
    else if (data instanceof Boolean)
      return ((((Boolean) data).booleanValue() == true) ? (long) 1 : (long) 0);
    throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
  }

  /*
   * parameter data�� short type���� conversion�Ͽ� return�Ѵ�. Conversion������
   * instance type Short, String, Byte, Boolean, Integer, Double, Float,
   * BigDecimal
   */

  static public short getShort(Object data) throws UJciException
  {
    if (data == null)
      return (short) 0;
    else if (data instanceof Number)
      return ((Number) data).shortValue();
    else if (data instanceof String)
    {
      try
      {
        return Short.parseShort((String) data);
      }
      catch (NumberFormatException e)
      {
        throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
      }
    }
    else if (data instanceof Boolean)
      return ((((Boolean) data).booleanValue() == true) ? (short) 1 : (short) 0);
    throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
  }

  /*
   * parameter data�� java.lang.String type���� conversion�Ͽ� return�Ѵ�.
   * Conversion������ instance type String, Byte, Boolean, Short, Integer,
   * Double, Float, BigDecimal
   */

  static public String getString(Object data) throws UJciException
  {
    if (data == null)
      return null;
    else if (data instanceof String)
      return ((String) data);
    else if ((data instanceof Number) || (data instanceof Boolean)
        || (data instanceof Date) || (data instanceof Time))
    {
      return data.toString();
    }
    else if (data instanceof Timestamp)
    {
      String form;

      if (CUBRIDTimestamp.isTimestampType((Timestamp)data))
      {
        form = "yyyy-MM-dd HH:mm:ss";
      }
      else
      {
        form = "yyyy-MM-dd HH:mm:ss.SSS";
      }

      java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(form);

      return f.format(data);
    }
    else if (data instanceof CUBRIDOID)
    {
      try
      {
        return ((CUBRIDOID) data).getOidString();
      }
      catch (Exception e)
      {
        return "";
      }
    }
    else if (data instanceof byte[])
    {
      return UGetTypeConvertedValue.getHexaDecimalString((byte[]) data);
    }
    throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
  }

  /*
   * parameter data�� java.sql.Time type���� conversion�Ͽ� return�Ѵ�.
   * Conversion������ instance type Time, String, Timestamp
   */

  static public Time getTime(Object data) throws UJciException
  {
    if (data == null)
      return null;
    else if (data instanceof Time)
      return new Time(((Time) data).getTime());
    else if (data instanceof String)
    {
      try
      {
        return Time.valueOf((String) data);
      }
      catch (IllegalArgumentException e)
      {
        throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
      }
    }
    else if (data instanceof Timestamp)
      return new Time(((Timestamp) data).getTime());
    throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
  }

  /*
   * parameter data�� java.sql.Timestamp type���� conversion�Ͽ� return�Ѵ�.
   * Conversion������ instance type Timestamp, String, Date, Time
   */

  static public Timestamp getTimestamp(Object data) throws UJciException
  {
    if (data == null)
      return null;
    else if (data instanceof Timestamp)
      return new Timestamp(((Timestamp) data).getTime());
    else if (data instanceof String)
    {
      try
      {
        return Timestamp.valueOf((String) data);
      }
      catch (IllegalArgumentException e)
      {
        throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
      }
    }
    else if (data instanceof Date)
      return new Timestamp(((Date) data).getTime());
    else if (data instanceof Time)
      return new Timestamp(((Time) data).getTime());
    throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
  }

  /*
   * byte [] type�� parameter�� CUBRID�� bit or bit varying type�̰� �� ������
   * 16���� ���� String object�� return�Ͽ� �ش�.
   */

  static private String getHexaDecimalString(byte[] data)
  {
    String stringData = "", aByteString;
    int temp = 0, halfByte;
    final short aByteSize = 256;

    for (int i = 0; i < data.length; i++)
    {
      if (data[i] < 0)
        temp = (short) data[i] + aByteSize;
      else
        temp = (short) data[i];
      aByteString = "";
      for (int j = 0; j < 2; j++)
      {
        halfByte = temp % 16;
        aByteString = ((halfByte < 10) ? String.valueOf(halfByte)
            : ((halfByte == 10) ? "a" : ((halfByte == 11) ? "b"
                : ((halfByte == 12) ? "c" : ((halfByte == 13) ? "d"
                    : ((halfByte == 14) ? "e" : "f"))))))
            + aByteString;
        temp /= 16;
      }
      stringData += aByteString;
    }
    return stringData;
  }

  /*
   * byte array data�� precision��ŭ�� length�� ���� String type����
   * conversion�Ͽ� return�Ѵ�.
   * 
   * 
   * static private String getBinaryDecimalString(int precision, byte[] data) {
   * String stringData="", aByteString=""; final short aByteSize = 256; int
   * temp;
   * 
   * for(int i=0 ; i< data.length ; i++){ if (data[i] < 0) temp = (short)
   * data[i] + aByteSize; else temp = (short) data[i]; aByteString=""; for(int
   * j=0 ; j<8 ; j++){ aByteString = String.valueOf(temp%2) + aByteString; temp
   * /= 2; } stringData += aByteString; } return stringData.substring(0,
   * precision); }
   */
}
