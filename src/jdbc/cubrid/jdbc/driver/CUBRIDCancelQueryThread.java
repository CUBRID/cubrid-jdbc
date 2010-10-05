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

package @CUBRID_DRIVER@;

import java.sql.*;


/**
 * Title:        CUBRID JDBC Driver
 * Description:
 * @version 2.0
 */


/**
 * �� class�� CUBRIDStatement�� CUBRIDPreparedStatement��
 * query timeout����� �����ϴµ� ���ȴ�.
 *
 * �����ÿ� �ð��� CUBRIDStatement object�� �ް� ������ ��
 * run()�� ȣ��Ǹ� �־��� �ð����� sleep�� �Ŀ�
 * �־��� CUBRIDStatement object�� cancel()�� ȣ�����ش�.
 *
 * �־��� CUBRIDStatement object�� query�� ������ ���� ������ �Ǹ�
 * queryended()�� ȣ���Ͽ� �˷��ְ� �Ǿ� �ִ�.
 *
 * query�� ������ server���� ������ ���������� �ұ��ϰ� ����
 * queryended()�� ȣ����� �ʾƼ� query�� ������ ����ϵ���
 * �õ��� �� ������ �߻��� ������ �����Ƿ�,
 * queryended()�Լ��� ȣ���ϴ� �Ͱ�
 * �־��� CUBRIDStatement object�� cancel()�� ȣ���ϴ� ����
 * ����ȭ�Ͽ� ������ �ϳ��� �������϶����� �ٸ� �ϳ��� block�ǵ��� �Ѵ�.
 */
class CUBRIDCancelQueryThread extends Thread {

/*
 * query�� ����� ������ ��ٸ� �ð�
 */
private int timeout;

/*
 * ����� query�� �������� CUBRIDStatement object
 */
private CUBRIDStatement stmt;

/*
 * query�� ������ ���������� ��Ÿ���� flag�̴�.
 * true�̸� query�� ������ �������Ƿ� �־��� CUBRIDStatement object��
 * cancel()�� ȣ������ �ʴ´�.
 * �� ���� target CUBRIDStatement object�� query�� ������ ������ true�� set�Ѵ�.
 */
private boolean end = false;

/*
 * ������ �ð��� CUBRIDStatement object�� �־�����.
 */
CUBRIDCancelQueryThread(CUBRIDStatement cancel_stmt, int time)
{
  stmt = cancel_stmt;
  timeout = time;
}

/*
 * �־��� timeout�� ���� sleep�� �Ŀ�
 * end���� false�̸�
 * CUBRIDStatement�� cancel�� ȣ���Ѵ�.
 */
public void run()
{
  try {
    Thread.sleep(timeout*1000);
    synchronized (this) {
      if ( end == false ) {
	stmt.cancel();
      }
    }
  }
  catch (Exception e) {
  }
}

/*
 * CUBRIDStatement object�� ���� ����Ǹ�
 * query�� ������ �������� �˸��� ����
 * end���� true�� set�Ѵ�.
 */
synchronized void queryended()
{
  end = true;
  interrupt();
}

}
