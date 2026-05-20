/*
 * Copyright 2024-2025 NetCracker Technology Corporation
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

package ${configuration.packageName};

import java.util.Map;
import java.util.List;

#foreach($import in ${object.imports})
import $import;
#end

/**
#foreach ($comment in $object.comments)
 * $comment
#end
#if ($object.comments.size() > 0)
 * <BR/>
 * 
#end
 */
${object.annotation}
public interface ${object.javaName} #if($object.implementz.size()>0)extends #foreach($impl in $object.implementz)$impl#if($foreach.hasNext), #end#end#end {
#foreach ($field in $object.fields)

#if ($field.comments.size() > 0)
	/**
#end	
#foreach ($comment in $field.comments)
	 * $comment
#end
#if ($field.comments.size() > 0)
	 */
#end
	${field.annotation}
	public void set${field.pascalCaseName}(${field.javaTypeFullClassname} ${field.javaName});

#if ($field.comments.size() > 0)
	/**
#end	
#foreach ($comment in $field.comments)
	 * $comment
#end
#if ($field.comments.size() > 0)
	 */
#end
	${field.annotation}
	public ${field.javaTypeFullClassname} get${field.pascalCaseName}();
#end
}
