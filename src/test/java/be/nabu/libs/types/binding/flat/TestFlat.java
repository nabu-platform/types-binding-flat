package be.nabu.libs.types.binding.flat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.text.ParseException;

import junit.framework.TestCase;
import be.nabu.libs.types.DefinedTypeResolverFactory;
import be.nabu.libs.types.TypeUtils;
import be.nabu.libs.types.api.ComplexContent;
import be.nabu.libs.types.binding.api.Window;
import be.nabu.libs.types.java.BeanInstance;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.ByteBuffer;

public class TestFlat extends TestCase {
	public void testFlatParse() throws IOException, ParseException {
		FlatBindingConfig config = FlatBindingConfig.load(Thread.currentThread().getContextClassLoader().getResource("binding.xml"));
		FlatBinding binding = new FlatBinding(DefinedTypeResolverFactory.getInstance().getResolver(), config, Charset.forName("UTF-8"));
		InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream("flat-input.csv");
		Company result = null;
		try {
			Window window = new Window("company/employees", 3, 3);
			ComplexContent content = binding.unmarshal(input, new Window [] { window });
			result = TypeUtils.getAsBean(content, Company.class);
			assertEquals("Nabu", result.getName());
			assertEquals("Organizational", result.getUnit());
			assertEquals("Nabu HQ", result.getAddress());
			assertEquals("BE666-66-66", result.getBillingNumber());
			assertEquals(24, result.getEmployees().size());
			assertEquals("John1", result.getEmployees().get(1).getFirstName());

			assertEquals(new Integer(31), result.getEmployees().get(0).getAge());
			assertEquals(new Integer(60), result.getEmployees().get(10).getAge());
			assertEquals(new Integer(44), result.getEmployees().get(14).getAge());
			
			assertEquals(new Integer(31), result.getEmployees().get(0).getAge());
			assertEquals(new Integer(57), result.getEmployees().get(1).getAge());
		}
		finally {
			input.close();
		}
		ByteBuffer buffer = IOUtils.newByteBuffer();
		binding.marshal(IOUtils.toOutputStream(buffer), new BeanInstance<Company>(result));
		System.out.println(IOUtils.toString(IOUtils.wrapReadable(buffer, binding.getCharset())));
	}
}
