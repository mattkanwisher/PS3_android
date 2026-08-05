// Describes the in-flight exception when the core routes std::terminate to
// report_fatal_error. Kept in its own translation unit because the rest of the
// bridge is built with -fno-exceptions, and rethrowing is the only way to get
// at the message.

#include <exception>
#include <string>
#include <typeinfo>

namespace chrysalis
{
	std::string describe_current_exception()
	{
		const std::exception_ptr ep = std::current_exception();
		if (!ep)
		{
			return {};
		}

		try
		{
			std::rethrow_exception(ep);
		}
		catch (const std::exception& e)
		{
			return std::string(typeid(e).name()) + ": " + e.what();
		}
		catch (...)
		{
			return "unknown non-std exception";
		}
	}
}
