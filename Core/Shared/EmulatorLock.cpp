#include "pch.h"
#include "Shared/EmulatorLock.h"
#include "Shared/Emulator.h"
#include "Shared/DebuggerRequest.h"
#ifndef MESEN_DISABLE_DEBUGGER
#include "Debugger/DebugBreakHelper.h"
#endif

EmulatorLock::EmulatorLock(Emulator *emu, bool allowDebuggerLock)
{
	_emu = emu;

	if(_emu->_runLock.IsLockedByCurrentThread()) {
		_emu->Lock();
	} else {
#ifndef MESEN_DISABLE_DEBUGGER
		if(allowDebuggerLock) {
			_debugger.reset(new DebuggerRequest(emu->GetDebugger(false)));
			if(_debugger->GetDebugger()) {
				_breakHelper.reset(new DebugBreakHelper(_debugger->GetDebugger(), true));
			} else {
				_debugger.reset();
				_emu->Lock();
			}
		} else {
			_emu->Lock();
		}
#else
		(void)allowDebuggerLock;
		_emu->Lock();
#endif
	}
}

EmulatorLock::~EmulatorLock()
{
#ifndef MESEN_DISABLE_DEBUGGER
	if(_debugger) {
		_breakHelper.reset();
	} else {
		_emu->Unlock();
	}
#else
	_emu->Unlock();
#endif
}
