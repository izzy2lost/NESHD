#pragma once
#include "pch.h"

class Emulator;
class DebuggerRequest;
#ifndef MESEN_DISABLE_DEBUGGER
class DebugBreakHelper;
#endif

class EmulatorLock
{
private:
	Emulator* _emu = nullptr;
	unique_ptr<DebuggerRequest> _debugger;
#ifndef MESEN_DISABLE_DEBUGGER
	unique_ptr<DebugBreakHelper> _breakHelper;
#endif

public:
	EmulatorLock(Emulator* emulator, bool allowDebuggerLock);
	~EmulatorLock();
};
