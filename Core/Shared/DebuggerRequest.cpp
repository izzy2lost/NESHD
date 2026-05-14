#include "pch.h"
#include "Shared/Emulator.h"
#include "Shared/DebuggerRequest.h"

DebuggerRequest::DebuggerRequest(Emulator* emu)
{
#ifndef MESEN_DISABLE_DEBUGGER
	if(emu) {
		_emu = emu;
		_debugger = _emu->_debugger.lock();
		_emu->_debugRequestCount++;
	}
#else
	(void)emu;
#endif
}

DebuggerRequest::DebuggerRequest(const DebuggerRequest& copy)
{
#ifndef MESEN_DISABLE_DEBUGGER
	_emu = copy._emu;
	_debugger = copy._emu->_debugger.lock();
	_emu->_debugRequestCount++;
#else
	(void)copy;
#endif
}

DebuggerRequest::~DebuggerRequest()
{
	if(_emu) {
		_emu->_debugRequestCount--;
	}
}
