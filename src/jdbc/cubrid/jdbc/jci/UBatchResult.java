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

/*
 * class UConnection method executeBatch,
 * ( Interface Statement method batchExecute�� ���� interface )
 * class UStatement method batchExecute
 * ( Interface PreparedStatement method batchExecute�� ���� interface )
 * �� ���� batch statement�� ���� �ΰ��� interface���� ������� result��
 * �����ϴ� class�̴�.
 *
 * since 2.0
 */

public class UBatchResult
{
  private boolean errorFlag;
  private int resultNumber; /* batch job���� ����� statement�� ���� */
  private int result[]; /* batch statement�� �� result count */
  private int statementType[]; /* batch statement�� �� statement type */
  private int errorCode[]; /* batch statement�� �� error code */
  private String errorMessage[]; /* batch statement�� �� error message */

  UBatchResult(int number)
  {
    resultNumber = number;
    result = new int[resultNumber];
    statementType = new int[resultNumber];
    errorCode = new int[resultNumber];
    errorMessage = new String[resultNumber];
    errorFlag = false;
  }

  /*
   * batch statement�� execution �� ������� �� statement�� error code��
   * return�Ѵ�.
   */

  public int[] getErrorCode()
  {
    return errorCode;
  }

  /*
   * batch statement�� execution �� ������� �� statement�� error Message��
   * return�Ѵ�.
   */

  public String[] getErrorMessage()
  {
    return errorMessage;
  }

  /*
   * batch statement�� execution �� ������� �� statement�� result count��
   * return�Ѵ�. error�� �߻��Ͽ��� ��� result count�� -3�̴�.
   */

  public int[] getResult()
  {
    return result;
  }

  /*
   * batch job���� execute�� statement ������ return�Ѵ�.
   */

  public int getResultNumber()
  {
    return resultNumber;
  }

  /*
   * batch statement�� �� statement�� type�� return�Ѵ�. class
   * CUBRIDCommandType���� type�� identify�� �� �ִ�.
   */

  public int[] getStatementType()
  {
    return statementType;
  }

  /*
   * error�� �߻����� ���� statement�� ����� set�ϱ� ���� interface�̴�. error
   * code�� 0����, error message�� null�� �����ȴ�.
   */

  synchronized void setResult(int index, int count)
  {
    if (index < 0 || index >= resultNumber)
      return;
    result[index] = count;
    errorCode[index] = 0;
    errorMessage[index] = null;
  }

  /*
   * error�� �߻��� statement�� ����� set�ϱ� ���� interface�̴�. result
   * count�� -3����, error code�� error message�� server�ʿ��� �Ѿ�� ������
   * set�Ѵ�.
   */

  synchronized void setResultError(int index, int code, String message)
  {
    if (index < 0 || index >= resultNumber)
      return;
    result[index] = -3;
    errorCode[index] = code;
    errorMessage[index] = message;
    errorFlag = true;
  }

  /*
   * index�� �ش��ϴ� statement�� type�� �����ϱ� ���� interface�̴�. statement
   * type�� class CUBRIDCommandType���� identify�� �� �ִ�.
   */

  public boolean getErrorFlag()
  {
    return errorFlag;
  }

  synchronized void setStatementType(int index, int type)
  {
    if (index < 0 || index >= resultNumber)
      return;
    statementType[index] = type;
  }
}
