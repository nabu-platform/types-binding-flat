# Flat File Binding

This binding implementation supports both fixed length and delimiter-based (or any combination thereof) flat files. It parses in a streaming fashion, and allows you to set windows to keep memory usage acceptable when dealing with large files.
Unlike other bindings like XML/JSON there is no "clear" link between the data and the type so you need a definition file to tell the engine how to parse/generate the files.

As an example check the unit tests which contain two beans that look like this: 

```java
public class Company {
	private String name, unit, address, billingNumber;
	private List<Employee> employees;
}
public static class Employee {
	private String id, firstName, lastName;
	private Integer age;
	private Date startDay;
}
```

The accompanying flat file looks like this:

```csv
Company,Nabu,Organizational
0,John0,Doe0,31
1,John1,Doe1,57,2013/12/03
2,John2,Doe2,50
3,John3,Doe3,57
4,John4,Doe4,35
5,John5,Doe5,19
6,John6,Doe6,21
7,John7,Doe7,43
8,John8,Doe8,40
9,John9,Doe9,31
10,John10,Doe10,60
11,John11,Doe11,48
12,John12,Doe12,35
13,John13,Doe13,56
14,John14,Doe14,44
15,John15,Doe15,48
16,John16,Doe16,27
17,John17,Doe17,28
18,John18,Doe18,26
19,John19,Doe19,47
20,John20,Doe20,36
21,John21,Doe21,34
22,John22,Doe22,31
23,John23,Doe23,31
Nabu HQ,BE666-66-66
```

To bind the data to the type, we need a binding file:

```xml
<binding complexType="be.nabu.libs.types.binding.flat.Company">
	<record separator="\n">
		<field separator="," fixed="Company"/>
		<field separator="," map="@name"/>
		<field map="@unit"/>
	</record>
	<record separator="\n" map="employees" >
		<field separator="," map="@id" match="[0-9]+" />
		<field separator="," map="firstName"/>
		<field separator="," map="lastName"/>
		<field separator="," map="age" match="[0-9]+" canEnd="true" />
		<field map="startDay" formatter="be.nabu.libs.types.simple.Date" format="yyyy/MM/dd"/>
	</record>
	<record separator="\n">
		<field separator="," map="address" />
		<field map="billingNumber"/>
	</record>
</binding>
```

A couple of things to note:

- This is separator based parsing and there are three record types in the root
- Each record type should (preferably) have at least one identifying field which can be done with a "fixed" attribute which is checked literally or a "match" attribute where the value is checked using a regex. You can have multiple identifiers per record
- Not all the records (or even fields) are actually mapped. The record structure is more in line with the types of records in the flat file than with the target structure
- You can perform custom formatting, in this case we parse a custom formatted date into a standardized java.util.Date

## Complex Example

In a more complex example implemented for a customer the file was a combination of separator-based records (linefeeds) with fixed length fields.

Additionally one "instance" consisted of a header, repeating elements and a footer but multiple instances could occur in a single file so it could be:

```
header1
element1
element2
footer1
header2
element3
element4
footer2
```

No additional separators to indicate the end of an instance and the start of the next. Additionally the footer was optional (the elements were optional too) so it could look like this:

```
header1
header2
footer2
header3
```

To achieve this we created a "wrapper" bean that contains a list of the actual elements we wanted.

The following is a small but representative part of the binding:

```xml
<binding complexType="com.example.Declarations">
	<record map="declarations">
	    <record separator="\n" map="header">
	        <field length="2" fixed="00"/>
	        <field length="1" map="type"/>
	        <field length="1" fixed="2"/>
	        <field length="15" map="reference"/>
	        <field length="10" map="number"/>
	        <field length="8" map="creationDate" formatter="com.example.custom.DateMarshaller"/>
	    </record>
	    <record separator="\n" map="elements" minOccurs="0" maxOccurs="0">
	        <field length="2" map="type" match="[A-Z]{1}[0-9]{1}" />
	        <field length="20" map="reference"/>
	        <field length="11" map="something/this"/>
	        <field length="48" map="something/else"/>
		</record>
 		<record separator="\n" map="footer">
	        <field length="2" fixed="11"/>
	        <field length="1" map="type"/>
	        <field length="1" fixed="2"/>
	        <field length="15" map="reference" description="The reference..."/>
		</record>
	</record>
</binding
```

Interesting to note:

- We added a root record to indicate the repeating nature of the declarations
- The "minOccurs" on the elements record means it is optional. The maxOccurs is set to 0 which means it is unbounded. Default for both these fields is 1.
- A mixture of separator based with fixed length. If the record is shorter than defined by the fields, the remaining fields are null. If the record is too long, you will get a parse exception. You can however explicitly set a length on the record as well which means it will also throw an exception if the record is shorter.

## Additional Notes

This readme is not complete yet but a few quick pointers:

### All fragments (records & fields)

- There is a default lookahead of 1mb, this means the parser will only look ahead by 1mb to try to match a fragment. If this is not enough, you can set the attribute "maxLookAhead" on the root binding element. You can also set a "maxLength" attribute on any fragment to override the binding default.
- Separators are usually fixed strings but you _can_ use regexes in which case you also need to set the attribute "separatorLength". For more information please check the utils-io delimiter logic.

### Records

- Min occurs and max occurs of records (default 1) are usually determined from the data type you map to but you can forcibly set this with the attributes "minOccurs" and "maxOccurs" on a record

### Fields

- You can set the attribute "pad" which will override the default space padding that is enabled on fixed length fields. You can fill a character or a string. The string will be repeated as much as possible and cut off (left or right depending on justify) if too long
- You can set the attribute "leftAlign" where you can set (true/false) whether or not the value for this field is left aligned. Default is false
- You can set the attribute "canEnd" where you can set (true/false) whether this field can end the record prematurely. It is basically telling the parser that if the record ends after this field, it's ok even if more fields are defined. This can be used to define optional fields at the end.
- The formatter field can take any formatter and once you have given it a formatter, you can define any attribute that it uses. For example the date formatter in the first example has format, timezone,...

# Complex bindings

By default the binding file will use the complex type defined in the root "binding" tag and all the fragments inside the binding to parse the flat file. It is however also possible to create more complex binding definitions where you can map multiple (named) records and reference other records to put them together in different ways. For example you could do:

```xml
<binding record="declaration">
	<record name="declaration" complexType="com.example.Declaration">
		<record parent="header" map="header"/>
		<record parent="element" map="elements"/>
		<record parent="footer" map="footer"/>
	</record>
	<record name="rejection" complexType="com.example.Declaration">
		<record parent="header" map="header"/>
		<record parent="rejectionElement" map="elements"/>
		<record parent="footer" map="footer"/>
	</record>
 	<record name="header" separator="\r\n">
	    <field length="2" fixed="00"/>
	    <field length="1" map="type"/>
	    <field length="1" fixed="2"/>
	    <field length="15" map="reference"/>
	    <field length="10" map="number"/>
	    <field length="8" map="creationDate" formatter="com.example.custom.DateMarshaller"/>
	</record>
 	<record name="element" separator="\r\n">
        <field length="2" map="type" match="[A-Z]{1}[0-9]{1}" />
        <field length="20" map="reference"/>
        <field length="11" map="something/this"/>
        <field length="48" map="something/else"/>
	</record>
 	<record name="rejectionElement" parent="element" separator="\r\n">
        <field length="100" map="rejectionReasons" />
	</record>
	<record name="footer" separator="\r\n">
        <field length="2" fixed="11"/>
        <field length="1" map="type"/>
        <field length="1" fixed="2"/>
        <field length="15" map="reference" description="The reference..."/>
	</record>
</binding>
```

The binding has a "default" root record which is "declaration", so unless you specify something else, this will be used to unmarshal/marshal the data. The declaration itself consists of three elements that are referenced by name.

Apart from the declaration there is also a rejection that is very similar to the original one but uses "rejectionElement" instead of "element". Note that the "rejectionElement" is actually also an extension of "element" so it will simply print out more.

To get a rejection binding, you can do this:

```java
// this will use the default "declaration"
FlatBinding binding = getFlatBinding();
// this will use the "rejection" record:
binding = binding.getNamedBinding("rejection");
```

# Performance

The performance of the parser depends on type and definition of the flat file. I have done some extensive testing using a binding that is based on the one in the complex binding example (cfr) but slightly more expansive:

- The header is 59 characters long and contains 9 fields
- The element is 519 characters long and contains 57 fields
- The footer is 148 characters long and contains 18 fields

As an example testcase I created a file with 56926 elements in it which was about 30mb in size.

The parser achieved a ratio of **1000 records per 50ms** which means it took about 2.8 seconds which means it parsed **20 records per ms**. Do note that the parser uses a lot of frameworks that use lazy loading so the first parse on a machine will have some overhead in that everything has to be initialized. However this is only once for the lifetime of the JVM and can be actively prevented by forcing an eager load.

Part of the overhead of the parsing is that the element has 57 fields in it. Suppose we have an element with the same length (519 characters) but instead of putting it in 57 fields we map it to two fields (the first field being the first two characters that act as identifier and everything else in the second field). At that point the parser achieved a throughput of **1000 records per 10ms** which comes down to **100 records per ms** giving you a **total processing time of 569ms**.

Note that there are a few things that can heavily influence how fast the parser operates:

- **Read buffer**: the default buffer size for a delimited container is set by length or maxLength. If neither is available, a default is used. A big buffer is faster for documents with a few large fields. However documents with a lot of small fields are better served by a small buffer (as the remaining data has to be pushed back and forth many times in such a scenario)
- **Regex separator**: a regex seperator can be up to 5 times slower than a fixed one, especially in combination with a large read buffer as the regex needs to be checked on the entire buffer

# TODO

- Add support for the ResultHandler interface so you can process records as they are parsed
- Add support for "strings" in delimited fields where the BackedDelimited can find a (non-escaped) quote to start a string, ignore any delimiter inside it until it finds a (non-escaped) quote to end the string. This can be used to allow the escape format of excel for both the linefeeds and the field delimiters. Note that a custom escape character (usually "\") should also be allowed but then we need to hold back not only the length of the separator but also that of the escape character(s)
- Add support for EDI in that the delimiters can be updated dynamically after reading something (perhaps can use the result handler for this?)
