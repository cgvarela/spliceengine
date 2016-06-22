/*

   Derby - Class com.splicemachine.db.diag.StatementCache

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package com.splicemachine.db.diag;

import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Collection;
import java.util.Iterator;
import java.util.Vector;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.reference.Limits;
import com.splicemachine.db.iapi.services.cache.CacheManager;
import com.splicemachine.db.iapi.services.context.ContextService;
import com.splicemachine.db.iapi.sql.ResultColumnDescriptor;
import com.splicemachine.db.iapi.sql.conn.LanguageConnectionContext;
import com.splicemachine.db.iapi.util.StringUtil;
import com.splicemachine.db.impl.jdbc.EmbedResultSetMetaData;
import com.splicemachine.db.impl.sql.GenericPreparedStatement;
import com.splicemachine.db.impl.sql.GenericStatement;
import com.splicemachine.db.impl.sql.conn.CachedStatement;
import com.splicemachine.db.vti.VTITemplate;

/**
	StatementCache is a virtual table that shows the contents of the SQL statement cache.
	
	This virtual table can be invoked by calling it directly.
	<PRE> select * from new com.splicemachine.db.diag.StatementCache() t</PRE>


	<P>The StatementCache virtual table has the following columns:
	<UL>
	<LI> ID CHAR(36) - not nullable.  Internal identifier of the compiled statement.
	<LI> SCHEMANAME VARCHAR(128) - nullable.  Schema the statement was compiled in.
	<LI> SQL_TEXT VARCHAR(32672) - not nullable.  Text of the statement
	<LI> UNICODE BIT/BOOLEAN - not nullable.  True if the statement is compiled as a pure unicode string, false if it handled unicode escapes.
	<LI> VALID BIT/BOOLEAN - not nullable.  True if the statement is currently valid, false otherwise
	<LI> COMPILED_AT TIMESTAMP nullable - time statement was compiled, requires STATISTICS TIMING to be enabled.


	</UL>
	<P>
	The internal identifier of a cached statement matches the toString() method of a PreparedStatement object for a Derby database.

	<P>
	This class also provides a static method to empty the statement cache, StatementCache.emptyCache()

*/
public final class StatementCache extends VTITemplate {

	private int position = -1;
	private Vector data;
	private GenericPreparedStatement currentPs;
	private boolean wasNull;

	public StatementCache() throws StandardException {

        DiagUtil.checkAccess();
        
        LanguageConnectionContext lcc = (LanguageConnectionContext)
            ContextService.getContextOrNull(LanguageConnectionContext.CONTEXT_ID);

        CacheManager statementCache = null;

		if (statementCache != null) {
			final Collection values = statementCache.values();
			data = new Vector(values.size());
			for (Iterator i = values.iterator(); i.hasNext(); ) {
				final CachedStatement cs = (CachedStatement) i.next();
				final GenericPreparedStatement ps =
					(GenericPreparedStatement) cs.getPreparedStatement();
				data.add(ps);
			}
		}
	}

	public boolean next() {

		if (data == null)
			return false;

		position++;

		for (; position < data.size(); position++) {
			currentPs = (GenericPreparedStatement) data.get(position);
	
			if (currentPs != null)
				return true;
		}

		data = null;
		return false;
	}

	public void close() {
		data = null;
		currentPs = null;
	}


	public String getString(int colId) {
		wasNull = false;
		switch (colId) {
		case 1:
			return currentPs.getObjectName();
		case 2:
			return ((GenericStatement) currentPs.statement).getCompilationSchema();
		case 3:
			String sql = currentPs.getSource();
			sql = StringUtil.truncate(sql, Limits.DB2_VARCHAR_MAXWIDTH);
			return sql;
		default:
			return null;
		}
	}

	public boolean getBoolean(int colId) {
		wasNull = false;
		switch (colId) {
		case 4:
			// was/is UniCode column, but since Derby 10.0 all
			// statements are compiled and submitted as UniCode.
			return true;
		case 5:
			return currentPs.isValid();
		default:
			return false;
		}
	}

	public Timestamp getTimestamp(int colId) {

		Timestamp ts = currentPs.getEndCompileTimestamp();
		wasNull = (ts == null);
		return ts;
	}

	public boolean wasNull() {
		return wasNull;
	}

	/*
	** Metadata
	*/
	private static final ResultColumnDescriptor[] columnInfo = {

		EmbedResultSetMetaData.getResultColumnDescriptor("ID",		  Types.CHAR, false, 36),
		EmbedResultSetMetaData.getResultColumnDescriptor("SCHEMANAME",    Types.VARCHAR, true, 128),
		EmbedResultSetMetaData.getResultColumnDescriptor("SQL_TEXT",  Types.VARCHAR, false, Limits.DB2_VARCHAR_MAXWIDTH),
		EmbedResultSetMetaData.getResultColumnDescriptor("UNICODE",   Types.BIT, false),
		EmbedResultSetMetaData.getResultColumnDescriptor("VALID",  Types.BIT, false),
		EmbedResultSetMetaData.getResultColumnDescriptor("COMPILED_AT",  Types.TIMESTAMP, true),

	};
	
	private static final ResultSetMetaData metadata = new EmbedResultSetMetaData(columnInfo);

	public ResultSetMetaData getMetaData() {

		return metadata;
	}
}