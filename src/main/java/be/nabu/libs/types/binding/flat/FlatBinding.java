package be.nabu.libs.types.binding.flat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.bind.annotation.adapters.XmlAdapter;
import javax.xml.namespace.QName;

import be.nabu.libs.converter.ConverterFactory;
import be.nabu.libs.converter.api.Converter;
import be.nabu.libs.property.api.Property;
import be.nabu.libs.property.api.Value;
import be.nabu.libs.resources.api.ReadableResource;
import be.nabu.libs.types.CollectionHandlerFactory;
import be.nabu.libs.types.DefinedTypeResolverFactory;
import be.nabu.libs.types.api.CollectionHandler;
import be.nabu.libs.types.api.CollectionHandlerProvider;
import be.nabu.libs.types.api.ComplexContent;
import be.nabu.libs.types.api.ComplexType;
import be.nabu.libs.types.api.DefinedTypeResolver;
import be.nabu.libs.types.api.Element;
import be.nabu.libs.types.api.MarshalException;
import be.nabu.libs.types.api.Marshallable;
import be.nabu.libs.types.api.Unmarshallable;
import be.nabu.libs.types.base.ValueImpl;
import be.nabu.libs.types.binding.BaseConfigurableTypeBinding;
import be.nabu.libs.types.binding.api.PartialUnmarshaller;
import be.nabu.libs.types.binding.api.Window;
import be.nabu.libs.types.binding.api.WindowedList;
import be.nabu.libs.types.binding.flat.FlatBindingConfig.Field;
import be.nabu.libs.types.binding.flat.FlatBindingConfig.Fragment;
import be.nabu.libs.types.binding.flat.FlatBindingConfig.Record;
import be.nabu.libs.types.java.BeanInstance;
import be.nabu.libs.types.properties.MaxOccursProperty;
import be.nabu.libs.types.properties.MinOccursProperty;
import be.nabu.libs.validator.api.ValidationMessage;
import be.nabu.libs.validator.api.ValidationMessage.Severity;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.CharBuffer;
import be.nabu.utils.io.api.CountingWritableContainer;
import be.nabu.utils.io.api.ReadableContainer;
import be.nabu.utils.io.api.WritableContainer;
import be.nabu.utils.io.containers.CountingReadableContainerImpl;
import be.nabu.utils.io.containers.EOFReadableContainer;
import be.nabu.utils.io.containers.LimitedMarkableContainer;
import be.nabu.utils.io.containers.chars.BackedDelimitedCharContainer;

/**
 * This class is NOT threadsafe
 */
public class FlatBinding extends BaseConfigurableTypeBinding<FlatBindingConfig> {

	private CollectionHandler collectionHandler = CollectionHandlerFactory.getInstance().getHandler();
	private Converter converter = ConverterFactory.getInstance().getConverter();
	
	private Charset charset;
	private ReadableResource resource;
	private long lookAhead = 409600;
	
	private boolean scopeMessages = false;
	private List<ValidationMessage> messages = new ArrayList<ValidationMessage>();
	
	public FlatBinding(FlatBindingConfig config, Charset charset) {
		this(DefinedTypeResolverFactory.getInstance().getResolver(), config, charset);
	}
	
	public FlatBinding(DefinedTypeResolver definedTypeResolver, FlatBindingConfig config, Charset charset) {
		super(definedTypeResolver, config);
		this.charset = charset;
	}
	
	public Charset getCharset() {
		return charset;
	}

	public FlatBinding getNamedBinding(String name) {
		FlatBindingConfig clone = null;
		for (Fragment child : getConfig().getChildren()) {
			if (child instanceof Record && name.equals(((Record) child).getName())) {
				clone = getConfig().clone();
				clone.setRecord(name);
				if (((Record) child).getComplexType() != null) {
					clone.setComplexType(((Record) child).getComplexType());
				}
			}
		}
		if (clone == null) {
			throw new IllegalArgumentException("No binding found with the name: " + name);
		}
		return new FlatBinding(clone, charset);
	}

	private String trailing;

	@Override
	protected ComplexContent unmarshal(ReadableResource resource, ComplexType type, Window[] windows, Value<?>... values) throws IOException, ParseException {
		this.resource = resource;
		ReadableContainer<ByteBuffer> bytes = resource.getReadable();
		ReadableContainer<CharBuffer> chars = IOUtils.wrapReadable(bytes, charset);
		LimitedMarkableContainer<CharBuffer> marked = new LimitedMarkableContainer<CharBuffer>(IOUtils.bufferReadable(chars, IOUtils.newCharBuffer(409600, true)), lookAhead);
		
		Record record = new Record();
		record.setDescription("Binding Root");
		if (getConfig().getRecord() != null) {
			for (Fragment child : getConfig().getChildren()) {
				if (child instanceof Record && getConfig().getRecord().equals(((Record) child).getName())) {
					record.getChildren().add(child);
					break;
				}
			}
		}
		else {
			record.setChildren(getConfig().getChildren());
		}
		marked.mark();
		ComplexContent newInstance = type.newInstance();
		EOFReadableContainer<CharBuffer> eof = new EOFReadableContainer<CharBuffer>(marked);
		String unmarshal = unmarshal(type.getName(), marked, eof, new CountingReadableContainerImpl<CharBuffer>(eof), record, newInstance, windows);
		// nothing was parsed correctly
		if (unmarshal == null) {
			// everything is probably trailing, just remove it
			trailing = null;
			throw new ParseException("Could not parse anything: " + formatMessages(), 0);
		}
		else {
			trailing = unmarshal + toString(marked);
			if (!trailing.isEmpty() && !getConfig().isAllowTrailing()) {
				throw new ParseException("Trailing characters not allowed: " + trailing, 0);
			}
			else {
				if (getConfig().getTrailingMatch() != null && !trailing.isEmpty() && !trailing.matches(getConfig().getTrailingMatch())) {
					throw new ParseException("The trailing section did not match the allowed regex '" + getConfig().getTrailingMatch() + "': " + trailing, 0);
				}
				return newInstance;
			}
		}
	}
	
	public boolean isScopeMessages() {
		return scopeMessages;
	}

	public void setScopeMessages(boolean scopeMessages) {
		this.scopeMessages = scopeMessages;
	}

	public List<ValidationMessage> getMessages() {
		return messages;
	}

	/**
	 * This method assumes the following:
	 * 		- fixed length is either a match (you can remark() the container) or a fail (reset())
	 * 		- delimiter is either a matcher with a possible remainder (send back remainder) or an exact match with no remainder
	 * The return value for this method means:
	 * 		- if there is something in it: push it back to the delimited (if any) or reset() + skip read - string.length
	 * 		- if it's an empty string: do nothing, it was an exact match
	 * 		- if it's null: do a reset(), nothing was matched 
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private String unmarshal(String path, LimitedMarkableContainer<CharBuffer> marked, EOFReadableContainer<CharBuffer> eof, CountingReadableContainerImpl<CharBuffer> counting, Fragment fragment, ComplexContent content, Window...windows) throws ParseException, IOException {
		// the delimited container (if any), it is used to keep track of whether or not the delimiter was found
		BackedDelimitedCharContainer delimited = null;
		
		// the readable used to actually read from
		// not all fields must be limited by a delimiter or length
		// for example child fields are naturally delimited by the record they belong to
		// root records don't need delimits because they might go to the end etc
		ReadableContainer<CharBuffer> readable = counting;
		
		if (fragment.getSeparator() != null) {
			String separator = normalizeSeparator(fragment.getSeparator());
			// we need a maxlength to scan for
			if (fragment.getMaxLength() != null) {
				readable = IOUtils.limitReadable(readable, fragment.getMaxLength());
			}
			// limit in size & separator
			if (fragment.getSeparatorLength() == null) {
				delimited = new BackedDelimitedCharContainer(readable, fragment.getLength() == null ? 4096 : fragment.getLength() + separator.length(), separator);
			}
			else {
				delimited = new BackedDelimitedCharContainer(readable, fragment.getLength() == null ? 4096 : fragment.getLength() + fragment.getSeparatorLength(), separator, fragment.getSeparatorLength());
			}
			readable = delimited;
		}
		// fixed length
		else if (fragment.getLength() != null) {
			readable = IOUtils.limitReadable(readable, fragment.getLength());
		}
		String pushback = "";
		if (fragment instanceof Record) {
			// need a correct offset to be able to skip later on
			long alreadyRead = counting.getReadTotal();
			long initialRead = alreadyRead;
			
			// this is kept to see if we have parsed anything for this record
			// if we parse something successfully and the next one is unsuccesful, we need to throw an exception
			// if the entire thing is unsuccessful, we return false
			boolean hasParsedAnything = false;
			record: for (Fragment child : ((Record) fragment).getChildren()) {
				if (child instanceof Record && child.getMap() != null) {
					child = ((Record) child).resolve(getConfig().getChildren());
					Element<?> childElement = content.getType().get(child.getMap());
					if (childElement == null) {
						throw new ParseException("The element " + child.getMap() + " does not exist in " + path, (int) alreadyRead);
					}
					if (!(childElement.getType() instanceof ComplexType)) {
						throw new ParseException("The record points to a child that is not complex", (int) alreadyRead);
					}
					Value<Integer> minOccurs = childElement.getProperty(new MinOccursProperty());
					int typeMinOccurs = minOccurs == null ? 1 : minOccurs.getValue();
					Value<Integer> maxOccurs = childElement.getProperty(new MaxOccursProperty());
					int typeMaxOccurs = maxOccurs == null ? 1 : maxOccurs.getValue();
					int recordCounter = 0;
					int maxRecordAmount = ((Record) child).getMaxOccurs() == null ? typeMaxOccurs : ((Record) child).getMaxOccurs();
					int minRecordAmount = ((Record) child).getMinOccurs() == null ? typeMinOccurs : ((Record) child).getMinOccurs();
					while(maxRecordAmount == 0 || recordCounter < maxRecordAmount) {
//						System.out.println("parsing " + child + "[" + recordCounter + "]");
						if (eof.isEOF()) {
							break record;
						}
						ComplexContent childContent = ((ComplexType) childElement.getType()).newInstance();
						String childPath = path + "/" + childElement.getName();
						CountingReadableContainerImpl<CharBuffer> childCounting = new CountingReadableContainerImpl<CharBuffer>(readable, alreadyRead);
						// the child is not a match
						pushback = unmarshal(childPath, marked, eof, childCounting, child, childContent, windows);
						// no match
						if (pushback == null) {
							// reset the parent counting correct
							counting.setReadTotal(initialRead);
							if (recordCounter < minRecordAmount) {
								// if we have parsed something, this is considered invalid
								if (hasParsedAnything) {
									throw new ParseException("The record " + child.getMap() + " does not have enough iterations: " + recordCounter + "/" + minRecordAmount + ", " + formatMessages(), (int) alreadyRead);
								}
								// otherwise it might just not be a match, have the parent reset
								else {
									messages.add(new ValidationMessage(Severity.WARNING, "Parsing " + child.getMap() + " failed after: " + recordCounter + " of [" + minRecordAmount + ", " + maxRecordAmount + "] iterations", (int) alreadyRead));
									return null;
								}
							}
							// if this is the last, don't send back null to indicate failure
							pushback = "";
							marked.reset();
							// reset the container to try the next fragment
							// if we continue parsing, reset the delimited, it is in an unknown state
							if (delimited != null) {
								delimited.reset();
							}
							break;
						}
						else {
							// update the alreadyread, the child read has set this correctly
							alreadyRead = childCounting.getReadTotal();
							// reset the parent so it's correct
							counting.setReadTotal(alreadyRead);
							
							// clear any messages up till now
							if (scopeMessages) {
								messages.clear();
							}
							
							hasParsedAnything = true;
							recordCounter++;
							marked.moveMarkAbsolute(alreadyRead);
							// push it back to the delimited if it is there, that means it is NOT stored in the marked! hence retain the pushback, it might be necessary to send it to the parent call
							if (delimited != null) {
								delimited.pushback(IOUtils.wrap(pushback));
							}
							else {
								marked.pushback(IOUtils.wrap(pushback));
								pushback = "";
							}
						}
						// if the type expects a list, it can be windowed
						if (typeMaxOccurs != 1) {
							// get the current value, see if there is a list already basically
							// Note: this only works with integer indexed collections
							Object currentObject = content.get(childElement.getName());
							int index = 0;
							if (currentObject != null) {
								CollectionHandlerProvider provider = collectionHandler.getHandler(currentObject.getClass());
								index = provider.getAsCollection(currentObject).size();
							}
							Window activeWindow = null;
							for (Window window : windows) {
								if (window.getPath().equals(childPath)) {
									activeWindow = window;
									break;
								}
							}
							if (activeWindow != null) {
								WindowedList list = null;
								// if the current object is already a list but it is empty (e.g. default initialization), overwrite it with a windowed list
								if (currentObject == null || (currentObject instanceof List && ((List) currentObject).isEmpty())) { 
									list = new WindowedList(resource, activeWindow, new PartialFlatUnmarshaller((Record) child, (ComplexType) content.getType().get(childElement.getName()).getType(), activeWindow, windows));
									content.set(childElement.getName(), list);
								}
								else if (currentObject instanceof WindowedList) {
									list = (WindowedList) currentObject;
								}
								else {
									throw new IllegalArgumentException("The collection already exists and is not windowed");
								}
								// always register the offset
								list.setOffset(index, alreadyRead);
								// only register the object if it is within the window size
								if (index < activeWindow.getSize()) {
									content.set(childElement.getName() + "[" + index + "]", childContent);
								}
							}
							else {
								// this reuses the internal collection handling
								content.set(childElement.getName() + "[" + index + "]", childContent);
							}
						}
						else {
							content.set(child.getMap(), childContent);
						}
					}
				}
				// it's either a record we don't need to map or a field
				else {
					CountingReadableContainerImpl<CharBuffer> childCounting = new CountingReadableContainerImpl<CharBuffer>(readable, alreadyRead);
					pushback = unmarshal(path, marked, eof, childCounting, child, content, windows);
					if (pushback == null) {
						counting.setReadTotal(initialRead);
						int minRecordAmount = !(child instanceof Record) || ((Record) child).getMinOccurs() == null ? 1 : ((Record) child).getMinOccurs();
						if (minRecordAmount != 0) {
							messages.add(new ValidationMessage(Severity.ERROR, "Could not parse '" + child + "' in: " + fragment, (int) alreadyRead));
							return null;
						}
						messages.add(new ValidationMessage(Severity.WARNING, "Could not parse '" + child + "' in: " + fragment, (int) alreadyRead));
						pushback = "";
						marked.reset();
						// reset the container to try the next fragment
						// if we continue parsing, reset the delimited, it is in an unknown state
						if (delimited != null) {
							delimited.reset();
						}
					}
					else {
						if (child instanceof Field) {
							childCounting.add(-pushback.length());
						}
						hasParsedAnything = true;
						// update the alreadyread;
						alreadyRead = childCounting.getReadTotal();
						if (!(child instanceof Field)) {
							marked.moveMarkAbsolute(alreadyRead);
							// clear any messages up till now
							if (scopeMessages) {
								messages.clear();
							}
						}
						// reset the parent so it's correct
						counting.setReadTotal(alreadyRead);
						if (delimited != null) {
							delimited.pushback(IOUtils.wrap(pushback));
						}
						else {
							marked.pushback(IOUtils.wrap(pushback));
							pushback = "";
						}
					}
				}
			}
			if (delimited != null) {
				// if we get here, it is possible the record was not read to the fullest (e.g. fixed length)
				String remainder = toString(readable);
				if (!remainder.isEmpty()) {
					throw new ParseException("There are dangling characters at the end of the " + fragment + ": '" + remainder + "'", (int) alreadyRead);
				}
				long hasActuallyRead = alreadyRead - initialRead;
				// check any length set on the entire fragment
				if (fragment.getLength() != null) {
					long shouldHaveRead = fragment.getLength();
					if (shouldHaveRead != hasActuallyRead) {
						throw new ParseException("There were not enough characters for the " + fragment + ": " + hasActuallyRead + " != " + shouldHaveRead, (int) alreadyRead);
					}
				}
				else if (fragment.getMinLength() != null) {
					if (hasActuallyRead < fragment.getMinLength()) {
						throw new ParseException("There were not enough characters for the " + fragment + ": " + hasActuallyRead + " < " + fragment.getMinLength(), (int) alreadyRead);
					}
				}
				// otherwise, if we have a fixed length, double check that
				else if (fragment.getLength() != null) {
					if (fragment.getLength() != alreadyRead) {
						throw new ParseException(fragment + " of wrong length: " +  alreadyRead + "/" + fragment.getLength(), (int) alreadyRead);
					}
				}
			}
			// make sure the delimited is counted into the offsets
			if (delimited != null && delimited.getMatchedDelimiter() != null) {
				counting.add(delimited.getMatchedDelimiter().length());
			}
		}
		// for a field, parse it and set itheader
		else {
			Field field = (Field) fragment;
			String value = toString(readable);
			if (delimited != null && !delimited.isDelimiterFound() && !field.isCanEnd()) {
				messages.add(new ValidationMessage(Severity.ERROR, "The field '" + field + "' is delimited with '" + field.getSeparator() + "' but no separator was found and this field is not optional", (int) counting.getReadTotal()));
				return null;
			}
			else if (field.getFixed() != null && !field.getFixed().equals(value)) {
				messages.add(new ValidationMessage(Severity.ERROR, "The field '" + field + "' does not have the correct fixed value, expecting '" + field.getFixed() + "', received '" + value + "'", (int) counting.getReadTotal()));
				return null;
			}
			else if (field.getMatch() != null && !value.matches(field.getMatch())) {
				messages.add(new ValidationMessage(Severity.ERROR, "The field '" + field + "' does not match the given regex, expecting match for '" + field.getMatch() + "', received '" + value + "'", (int) counting.getReadTotal()));
				return null;
			}
			if (field.getMap() != null) {
				// if it's fixed length, it might be padded
				if (field.getLength() != null) {
					String pad = field.getPad() == null ? " " : field.getPad();
					if (field.isLeftAlign()) {
						while (value.endsWith(pad)) {
							value = value.substring(0, value.length() - pad.length());
						}
					}
					else {
						while (value.startsWith(pad)) {
							value = value.substring(pad.length());
						}
					}
				}
				if (field.getMinLength() != null && value.length() < field.getMinLength()) {
					messages.add(new ValidationMessage(Severity.ERROR, "The field '" + field + "' does not have enough characters:" + value.length() + " < " + field.getMinLength(), (int) counting.getReadTotal()));
					return null;
				}
				Object unmarshalledValue = value;
				if (value.isEmpty()) {
					unmarshalledValue = null;
				}
				// check if we want to use a formatter
				else if (field.getFormatter() != null) {
					try {
						Object formatterInstance = Thread.currentThread().getContextClassLoader().loadClass(field.getFormatter()).newInstance();
						if (formatterInstance instanceof Unmarshallable) {
							Unmarshallable<?> unmarshallable = (Unmarshallable<?>) formatterInstance;
							List<Value<?>> values = new ArrayList<Value<?>>();
							for (Property<?> property : unmarshallable.getSupportedProperties()) {
								QName qname = new QName(property.getName());
								if (field.getOtherAttributes() != null && field.getOtherAttributes().containsKey(qname)) {
									values.add(new ValueImpl(property, converter.convert(field.getOtherAttributes().get(qname), property.getValueClass())));
								}
							}
							unmarshalledValue = unmarshallable.unmarshal(value, values.toArray(new Value[0]));
						}
						else if (formatterInstance instanceof XmlAdapter) {
							XmlAdapter adapter = (XmlAdapter) formatterInstance;
							try {
								unmarshalledValue = adapter.unmarshal(value);
							}
							catch (Exception e) {
								throw new ParseException("The formatter " + field.getFormatter() + " failed to unmarshal the value: " + e.getMessage(), 0);
							}
						}
						else {
							throw new ParseException("Unknown unmarshaller: " + field.getFormatter(), 0);
						}
					}
					catch (InstantiationException e) {
						throw new ParseException("Can not instantiate formatter " + field.getFormatter(), 0);
					}
					catch (IllegalAccessException e) {
						throw new ParseException("Can not instantiate formatter " + field.getFormatter(), 0);
					}
					catch (ClassNotFoundException e) {
						throw new ParseException("Can not find formatter " + field.getFormatter(), 0);
					}
				}
				// if no custom formatter is used, the default conversion logic will be used
				content.set(field.getMap(), unmarshalledValue);
			}
		}
		if (pushback != null && delimited != null && delimited.getRemainder() != null) {
			pushback += delimited.getRemainder();
		}
		return pushback;
	}
	
	
	private char [] stringificationBuffer = new char[4096];
	
	/**
	 * This is a copy of the IOUtils.toString() method with the exception that the used char array does not have to be instantiated every time
	 * This appears to make a ~30% difference in performance
	 */
	public String toString(ReadableContainer<CharBuffer> readable) throws IOException {
		StringBuilder builder = new StringBuilder();
		long read = 0;
		while ((read = readable.read(IOUtils.wrap(stringificationBuffer, false))) > 0) {
			builder.append(new String(stringificationBuffer, 0, (int) read));
		}
		return builder.toString();
	}
	
	private String normalizeSeparator(String separator) {
		return separator.replace("\\n", "\n")
			.replace("\\r", "\r");
	}
	
	@Override
	public void marshal(OutputStream output, ComplexContent content, Value<?>...values) throws IOException {
		Record record = new Record();
		if (getConfig().getRecord() != null) {
			for (Fragment child : getConfig().getChildren()) {
				if (child instanceof Record && getConfig().getRecord().equals(((Record) child).getName())) {
					record.getChildren().add(child);
					break;
				}
			}
		}
		else {
			record.setChildren(getConfig().getChildren());
		}
		marshal(IOUtils.wrapWritable(IOUtils.wrap(output), charset), record, content);
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void marshal(WritableContainer<CharBuffer> output, Fragment fragment, ComplexContent content) throws IOException, MarshalException {
		if (fragment instanceof Record) {
			if (fragment.getMap() != null) {
				Object object = content.get(fragment.getMap());
				if (object != null) {
					Element<?> definition = content.getType().get(fragment.getMap());
					Value<Integer> maxOccurs = definition.getProperty(new MaxOccursProperty());
					// it's a list, we need to loop
					if (maxOccurs != null && maxOccurs.getValue() != 1) {
						CollectionHandlerProvider provider = collectionHandler.getHandler(object.getClass());
						for (Object child : provider.getAsCollection(object)) {
							if (!(child instanceof ComplexContent)) {
								child = new BeanInstance(child);
							}
							marshalRecord(output, (Record) fragment, (ComplexContent) child);
						}
					}
					else {
						if (!(object instanceof ComplexContent)) {
							object = new BeanInstance(object);
						}
						marshalRecord(output, (Record) fragment, (ComplexContent) object);
					}
				}
			}
			else {
				marshalRecord(output, (Record) fragment, content);
			}
		}
		// you can have fixed fields not mapped from the source
		else if (((Field) fragment).getFixed() != null) {
			String value = ((Field) fragment).getFixed();
			if (fragment.getSeparator() != null) {
				value += normalizeSeparator(fragment.getSeparator());
			}
			output.write(IOUtils.wrap(value));
		}
		// this will map the fields that are mapped from the source or are basically not mapped at all (like a spaceholder for fixed length fields that are not mapped)
		else {
			Field field = (Field) fragment;
			Object object = fragment.getMap() != null ? content.get(fragment.getMap()) : null;
			String mappedValue = null;
			if (object != null && field.getFormatter() != null) {
				try {
					Object formatterInstance = Thread.currentThread().getContextClassLoader().loadClass(field.getFormatter()).newInstance();
					if (formatterInstance instanceof Marshallable) {
						Marshallable marshallable = (Marshallable<?>) formatterInstance;
						List<Value<?>> values = new ArrayList<Value<?>>();
						for (Property<?> property : marshallable.getSupportedProperties()) {
							QName qname = new QName(property.getName());
							if (field.getOtherAttributes() != null && field.getOtherAttributes().containsKey(qname)) {
								values.add(new ValueImpl(property, converter.convert(field.getOtherAttributes().get(qname), property.getValueClass())));
							}
						}
						mappedValue = marshallable.marshal(object, values.toArray(new Value[0]));
					}
					else if (formatterInstance instanceof XmlAdapter) {
						XmlAdapter adapter = (XmlAdapter) formatterInstance;
						try {
							mappedValue = (String) adapter.marshal(object);
						}
						catch (Exception e) {
							throw new MarshalException("The formatter " + field.getFormatter() + " failed to unmarshal the value: " + e.getMessage(), e);
						}
					}
					else {
						throw new MarshalException("Unknown marshaller: " + field.getFormatter());
					}
				}
				catch (InstantiationException e) {
					throw new MarshalException("Can not instantiate formatter " + field.getFormatter(), e);
				}
				catch (IllegalAccessException e) {
					throw new MarshalException("Can not instantiate formatter " + field.getFormatter(), e);
				}
				catch (ClassNotFoundException e) {
					throw new MarshalException("Can not find formatter " + field.getFormatter(), e);
				}
			}
			// otherwise, if the object is not null, use default conversion
			else if (object != null) {
				mappedValue = converter.convert(object, String.class);
			}
			else {
				mappedValue = "";
			}
			if (fragment.getLength() != null) {
				String pad = field.getPad() == null ? " " : field.getPad();
				while (mappedValue.length() < fragment.getLength()) {
					if (field.isLeftAlign()) {
						mappedValue += pad;
					}
					else {
						mappedValue = pad + mappedValue;
					}
				}
				// this can happen if the pad is too long
				if (mappedValue.length() > fragment.getLength()) {
					if (field.isLeftAlign()) {
						mappedValue = mappedValue.substring(0, fragment.getLength());
					}
					else {
						mappedValue = mappedValue.substring(mappedValue.length() - fragment.getLength());
					}
				}
			}
			if (fragment.getSeparator() != null) {
				mappedValue += normalizeSeparator(fragment.getSeparator());
			}
			output.write(IOUtils.wrap(mappedValue));
		}
	}
	
	private void marshalRecord(WritableContainer<CharBuffer> output, Record record, ComplexContent content) throws IOException {
		CountingWritableContainer<CharBuffer> counted = IOUtils.countWritable(output);
		for (Fragment childFragment : record.getChildren()) {
			if (childFragment instanceof Record) {
				childFragment = ((Record) childFragment).resolve(getConfig().getChildren());
			}
			marshal(counted, childFragment, content);
		}
		if (record.getSeparator() != null) {
			output.write(IOUtils.wrap(normalizeSeparator(record.getSeparator())));
		}
		else if (record.getLength() != null) {
			for (long i = counted.getWrittenTotal(); i < record.getLength(); i++) {
				output.write(IOUtils.wrap(" "));
			}
		}
	}
	
	public class PartialFlatUnmarshaller implements PartialUnmarshaller {

		private Record record;
		private ComplexType type;
		private Window thisWindow;
		private List<Window> otherWindows;
		
		public PartialFlatUnmarshaller(Record record, ComplexType type, Window thisWindow, Window...allWindows) {
			this.record = record;
			this.type = type;
			this.thisWindow = thisWindow;
			// make sure we remove the active window, otherwise the path will be reused for windowing!
			this.otherWindows = new ArrayList<Window>(Arrays.asList(allWindows));
			this.otherWindows.remove(thisWindow);
		}
		
		@Override
		public List<ComplexContent> unmarshal(InputStream input, long offset, int batchSize) throws IOException, ParseException {
			ReadableContainer<CharBuffer> readable = IOUtils.wrapReadable(IOUtils.wrap(input), charset);
			if (IOUtils.copyChars(readable, IOUtils.newCharSink(offset)) != offset) {
				throw new IOException("Could not skip to position " + offset);
			}
			List<ComplexContent> entries = new ArrayList<ComplexContent>();
			LimitedMarkableContainer<CharBuffer> marked = new LimitedMarkableContainer<CharBuffer>(readable, 0);
			marked.mark();
			for (int i = 0; i < batchSize; i++) {
				EOFReadableContainer<CharBuffer> eof = new EOFReadableContainer<CharBuffer>(marked);
				CountingReadableContainerImpl<CharBuffer> counting = new CountingReadableContainerImpl<CharBuffer>(eof, offset);
				ComplexContent content = type.newInstance();
				String pushback = FlatBinding.this.unmarshal(thisWindow.getPath(), marked, eof, counting, record, content, otherWindows.toArray(new Window[0]));
				if (pushback == null) {
					throw new ParseException("Can not reparse windowed elements", 0);
				}
				entries.add(content);
				offset = counting.getReadTotal() - pushback.length();
				marked.remark();
				marked.pushback(IOUtils.wrap(pushback));
			}
			return entries;
		}
		
	}

	private String formatMessages() {
		StringBuilder builder = new StringBuilder();
		for (ValidationMessage message : getMessages()) {
			if (!builder.toString().isEmpty()) {
				builder.append(",\n\t");
			}
			builder.append("[" + message.getSeverity() + ":" + message.getCode() + "] " + message.getMessage());
		}
		return builder.toString();
	}
}
