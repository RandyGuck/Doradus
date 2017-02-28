/*
 * Copyright (C) 2014 Dell, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dell.doradus.common;

import static com.dell.doradus.common.Utils.require;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Holds the definition of an application, including its options, tables, and their
 * fields.
 */
final public class ApplicationDefinition implements JSONable {
    // Application name (case-sensitive):
    private String m_appName;
    
    // Map of options used by this application. Option names are case-sensitive.
    private final Map<String, String> m_optionMap =
        new LinkedHashMap<String, String>();
    
    // Map of table names (case-sensitive) to TableDefinitions:
    private final SortedMap<String, TableDefinition> m_tableMap =
        new TreeMap<String, TableDefinition>();

    /**
     * Test the given string for validity as an application name. A valid application
     * name must begin with a letter and contain only letters, digits, and underscores.
     * 
     * @param  appName  Candidate application name.
     * @return          True if name is syntactically valid.
     */
    public static boolean isValidName(String appName) {
        return appName != null &&
               appName.length() > 0 &&
               Utils.isLetter(appName.charAt(0)) &&
               Utils.allAlphaNumUnderscore(appName);
    }
    
    /**
     * Create a new empty ApplicationDefinition object. The object is not valid until
     * members have been set from parsing or set methods.
     */
    public ApplicationDefinition() { }

    /**
     * Parse the application definition rooted at given UNode tree and copy its contents
     * into this object. The root node is the "application" object, so its name is the
     * application name and its child nodes are definitions such as "key", "options",
     * "fields", etc. An exception is thrown if the definition contains an error.
     *  
     * @param appNode   Root of a UNode tree that defines an application.
     */
    public void parse(UNode appNode) {
        assert appNode != null;
        
        setAppName(appNode.getName());
        for (String childName : appNode.getMemberNames()) {
            UNode childNode = appNode.getMember(childName);
            switch (childName) {
            case "key":
                // Silently ignore
                break;

            case "options":
                for (String optName : childNode.getMemberNames()) {
                    UNode optNode = childNode.getMember(optName);
                    require(optNode.isValue(), "'option' must be a value: " + optNode);
                    setOption(optNode.getName(), optNode.getValue());
                }
                break;

            case "tables":
                require(m_tableMap.size() == 0, "'tables' can be specified only once");
                for (UNode tableNode : childNode.getMemberList()) {
                    TableDefinition tableDef = new TableDefinition();
                    tableDef.parse(tableNode);
                    addTable(tableDef);
                }
                break;

            default:
                require(false, "Unrecognized 'application' element: " + childName);
            }
        }
        
        verify();
    }

    ///// Getters

    /**
     * Return this application's name.
     * 
     * @return  Application name (case-sensitive).
     */
    public String getAppName() {
        return m_appName;
    }
    
    /**
     * Indicate whether or not this application tables to be implicitly created.
     * 
     * @return  True if this application tables to be implicitly created.
     */
    public boolean allowsAutoTables() {
        String optValue = getOption(CommonDefs.AUTO_TABLES);
        return optValue != null && optValue.equalsIgnoreCase("true");
    }
    
    /**
     * Get the value of the given option such as "AutoTables". Null is returned if the
     * option has not been set.
     *  
     * @param optName   Option name (case-sensitive).
     * @return          Option value or null if no value has been set.
     */
    public String getOption(String optName) {
        return m_optionMap.get(optName);
    }
    
    /**
     * Get a Set of all option names currently defined for this application. For
     * each option name in the returned set, {@link #getOption(String)} can be called to
     * get the value of that option.
     * 
     * @return  Set of all option names currently defined for this table. The set will be
     *          empty if no options are defined.
     */
    public Set<String> getOptionNames() {
        return m_optionMap.keySet();
    }
    
    /**
     * Get the StorageService option for this application. Null is returned if the option
     * has not been set.
     * 
     * @return  The StorageService option for this application.
     */
    public String getStorageService() {
        return getOption(CommonDefs.OPT_STORAGE_SERVICE);
    }
    
    /**
     * Return the {@link TableDefinition} for the table with the given name or null if
     * this application does not know about such a table.
     * 
     * @param tableName Name of table.
     * @return          {@link TableDefinition} of corresponding table, or null if
     *                  unknown to this application.
     */
    public TableDefinition getTableDef(String tableName) {
        return m_tableMap.get(tableName);
    }
    
    /**
     * Get the map of all {@link TableDefinition}s owned by this application indexed by
     * table name. This map is not copied so the caller must only read!
     * 
     * @return  The map of all {@link TableDefinition}s owned by this application.
     */
    public Map<String, TableDefinition> getTableDefinitions() {
        return m_tableMap;
    }

    /**
     * Get this application's definition including tables, schedules, etc. as a
     * {@link UNode} tree.
     * 
     * @return  The root of a {@link UNode} tree.
     */
    public UNode toDoc() {
        // The root node is always a MAP whose name is the application's name. In case it
        // is serialized to XML, we set this node's tag name to "application".
        UNode appNode = UNode.createMapNode(m_appName, "application");
        if (m_optionMap.size() > 0) {
            UNode optsNode = appNode.addMapNode("options");
            for (String optName : m_optionMap.keySet()) {
                // Set each option's tag name to "option" for XML's sake.
                optsNode.addValueNode(optName, m_optionMap.get(optName), "option");
            }
        }
        if (m_tableMap.size() > 0) {
            UNode tablesNode = appNode.addMapNode("tables");
            for (TableDefinition tableDef : m_tableMap.values()) {
                tablesNode.addChildNode(tableDef.toDoc());
            }
        }
        
        return appNode;
    }
    
    // For debugging:
    @Override
    public String toString() {
        return "Application '" + m_appName + "'";
    }
    
    ///// Setters
    
    /**
     * Set this ApplicationDefinition's application name to the given value. An exception
     * is thrown if the name is invalid. This method should only be used when building-up
     * an application definition: it does not change the name of an existing application.
     * 
     * @param appName   Application name for this definition.
     */
    public void setAppName(String appName) {
        require(isValidName(appName), "Invalid application name: " + appName);
        m_appName = appName;
    }
    
    /**
     * Set the option with the given name to the given value. If the option value is null,
     * the option is "unset". If the option has an existing value, it is replaced.
     * 
     * @param optName   Name of option to set (case-sensitive).
     * @param optValue  New value of option or null to "unset".
     */
    public void setOption(String optName, String optValue) {
        if (optValue == null) {
            m_optionMap.remove(optName);
        } else {
            m_optionMap.put(optName, optValue.trim());
        }
    }
    
    /**
     * Add the given table definition to this application. This method assumes that the
     * table definition has been validated and the corresponding database table has been
     * (or will be) created. It throws an IllegalArgumentException if the table definition
     * currently belongs to an application with a different name or its name is not unique
     * within the already-defined tables. If the table definition looks OK, it is
     * transferred to this application by adding it to the table map and by setting the
     * table's application definition to us. 
     * 
     * @param  tableDef {@link TableDefinition} of a new table.
     */
    public void addTable(TableDefinition tableDef) {
        require(!m_tableMap.containsKey(tableDef.getTableName()),
                "Attempt to add duplicate table: " + tableDef.getTableName());
        tableDef.setApplication(this);
        m_tableMap.put(tableDef.getTableName(), tableDef);
    }

    /**
     * Remove the given table from this application, presumably because it has been
     * deleted.
     * 
     * @param tableDef  {@link TableDefinition} to be deleted.
     */
    public void removeTableDef(TableDefinition tableDef) {
        assert tableDef != null;
        assert tableDef.getAppDef() == this;
        
        m_tableMap.remove(tableDef.getTableName());
    }

    ///// Builder interface
    
    /**
     * Create a new {@link Builder} object. This is a convenience method that calls
     * {@code new Builder()}.
     * 
     * @return  New {@link Builder} object.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Helper class for building an {@link ApplicationDefinition} object using the
     * builder pattern.
     */
    public static class Builder {
        private final ApplicationDefinition m_appDef = new ApplicationDefinition();
        
        /**
         * Create a new {@link Builder} object to begin the construction of a new
         * {@link ApplicationDefinition}.
         */
        public Builder() {}
        
        /**
         * Verify and return the completed {@link ApplicationDefinition}. An exception is
         * thrown if any inconsistencies are detected in definition such as links that do
         * not agree with each other.
         *  
         * @return  Verified and completed {@link ApplicationDefinition}.
         */
        public ApplicationDefinition build() {
            m_appDef.verify();
            return m_appDef;
        }
        
        /**
         * Set the application's name.
         * 
         * @param appName   Application name.
         * @return          This {@link Builder}.
         */
        public Builder withName(String appName) {
            m_appDef.setAppName(appName);
            return this;
        }
        
        /**
         * Set the given application option. If the option is already defined, it's value
         * is replaced unless the given value is null, in which case the option is unset.
         * 
         * @param optName   Application option name.
         * @param optValue  New value for option or null to unset the option.
         * @return          This {@link Builder}.
         */
        public Builder withOption(String optName, String optValue) {
            m_appDef.setOption(optName, optValue);
            return this;
        }
        
        /**
         * Add the given table definition to the application.
         * 
         * @param tableDef  {@link TableDefinition} of table to add.
         * @return          This {@link Builder}.
         */
        public Builder withTable(TableDefinition tableDef) {
            m_appDef.addTable(tableDef);
            return this;
        }
    }
    
    ///// Private Methods
    
    // Verify that the application is complete by checking that all link fields are
    // complete and in agreement.
    private void verify() {
        // Copy table defs to avoid ConcurrentModificationException if we add a table.
        List<TableDefinition> definedTables = new ArrayList<>(m_tableMap.values());
        for (TableDefinition tableDef : definedTables) {
            // Copy field defs for same reason
            List<FieldDefinition> definedFields = new ArrayList<>(tableDef.getFieldDefinitions());
            for (FieldDefinition fieldDef : definedFields) {
                if (fieldDef.isLinkType()) {
                    verifyLink(tableDef, fieldDef);
                }
            }
        }
    }
    
    // Verify the the given link agrees with its inverse. If it references a table that
    // has not been defined, define it automatically. If it refers to an inverse that
    // has not been defined, add it automatically.
    private void verifyLink(TableDefinition tableDef, FieldDefinition linkDef) {
        String extent = linkDef.getLinkExtent();
        TableDefinition extentTableDef = getTableDef(extent);
        if (extentTableDef == null) {
            extentTableDef = new TableDefinition();
            extentTableDef.setTableName(extent);
            tableDef.getAppDef().addTable(extentTableDef);
        }
        
        String inverse = linkDef.getLinkInverse();
        FieldDefinition inverseLinkDef = extentTableDef.getFieldDef(inverse);
        if (inverseLinkDef == null) {
            inverseLinkDef = new FieldDefinition(inverse);
            inverseLinkDef.setType(linkDef.getType());
            inverseLinkDef.setLinkInverse(linkDef.getName());
            inverseLinkDef.setLinkExtent(tableDef.getTableName());
            extentTableDef.addFieldDefinition(inverseLinkDef);
        } else {
            require(inverseLinkDef.getType() == linkDef.getType() &&  // both link or xlink
                    inverseLinkDef.getLinkExtent().equals(tableDef.getTableName()) &&
                    inverseLinkDef.getLinkInverse().equals(linkDef.getName()),
                "Link '%s' in table '%s' conflicts with inverse field '%s' in table '%s'",
                    linkDef.getName(), tableDef.getTableName(),
                    inverseLinkDef.getName(), extentTableDef.getTableName());
        }
    }
    
    /**
     * Replaces of all occurences of aliases defined with this table, by their expressions.
     * Now a simple string.replace is used. 
     * 
     * @param str string to replace
     * @return string with replaced aliases. If there were no aliases, the string is unchanged.
     */
	public String replaceAliaces(String str) {
		if(str == null) return str;
		if(str.indexOf(CommonDefs.ALIAS_FIRST_CHAR) < 0) return str;
		
		PriorityQueue<AliasDefinition> aliasQueue = new PriorityQueue<AliasDefinition>(11, new Comparator<AliasDefinition>() {
			public int compare(AliasDefinition alias1, AliasDefinition alias2) {
				return alias2.getName().length() - alias1.getName().length();
			}
		});
		
		for(TableDefinition tableDef : getTableDefinitions().values()) {
			for(AliasDefinition aliasDef : tableDef.getAliasDefinitions()) {
				aliasQueue.add(aliasDef);					
			}
		}
		while(true) {
			String newstring = str; 
			 while (aliasQueue.size() != 0) {			        
				AliasDefinition aliasDef = aliasQueue.poll();
		        newstring = newstring.replace(aliasDef.getName(), aliasDef.getExpression());
			}	 
			if(newstring.equals(str)) break;
			str = newstring;
		}
		return str;
	}

	@Override
	public String toJSON() {
		return toDoc().toJSON();
	}

}
