#include "EspeonProcessorSetup.h"
#include "EspeonAnimSettings.h"

#include "Async/Async.h"
#include "Dom/JsonObject.h"
#include "HAL/FileManager.h"
#include "HAL/PlatformMisc.h"
#include "HAL/PlatformProcess.h"
#include "Misc/FileHelper.h"
#include "Misc/Paths.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"

DEFINE_LOG_CATEGORY_STATIC(LogEspeonSetup, Log, All);

#define LOCTEXT_NAMESPACE "EspeonProcessorSetup"

namespace
{
	TSharedPtr<FJsonObject> ParseJsonObject(const FString& Text)
	{
		TSharedPtr<FJsonObject> Obj;
		if (!FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Text), Obj)) return nullptr;
		return Obj;
	}

	FString CleanDir(FString Dir)
	{
		Dir = FPaths::ConvertRelativePathToFull(Dir);
		FPaths::NormalizeDirectoryName(Dir);
		Dir.RemoveFromEnd(TEXT("/"));
		return Dir;
	}
}

FEspeonProcessorSetup& FEspeonProcessorSetup::Get()
{
	static FEspeonProcessorSetup Instance;
	return Instance;
}

FString FEspeonProcessorSetup::GetSetupLogPath()
{
	return FPaths::ConvertRelativePathToFull(FPaths::Combine(FPaths::ProjectSavedDir(), TEXT("EspeonAnim/setup.log")));
}

FText FEspeonProcessorSetup::GetLicenseNotice()
{
	return LOCTEXT("License",
		"This installs the EspeonAnim processor (Python environment, PyTorch, GEM-X) and downloads about 7.5 GB of models. "
		"It needs git, an internet connection and a few minutes to an hour. Administrator rights are not required.\n\n"
		"By continuing you accept the licenses of the downloaded models:\n"
		"  - GEM-X weights (nvidia/GEM-X): NVIDIA Open Model License. The backbone embeds DINOv3 weights.\n"
		"  - SOMA-X assets (nvidia/SOMA-X v0.3.3): Apache-2.0.\n"
		"  - MediaPipe pose_landmarker_heavy: Apache-2.0.\n"
		"  - GEM-X source code (NVlabs/gem-x): Apache-2.0.\n\n"
		"Install or repair the processor now?");
}

FEspeonProcessorStatus FEspeonProcessorSetup::GetStatus() const
{
	FScopeLock L(&Lock);
	return Status;
}

void FEspeonProcessorSetup::SetStatus(TFunctionRef<void(FEspeonProcessorStatus&)> Fn)
{
	FScopeLock L(&Lock);
	Fn(Status);
}

void FEspeonProcessorSetup::CheckAsync()
{
	if (bInstalling || bChecking.Exchange(true)) return;
	SetStatus([](FEspeonProcessorStatus& S)
	{
		S.State = EEspeonProcessorState::Checking;
		S.Text = TEXT("Checking processor...");
	});
	Async(EAsyncExecution::ThreadPool, [this]()
	{
		RunDoctor();
		bChecking = false;
	});
}

void FEspeonProcessorSetup::RunDoctor()
{
	const UEspeonAnimSettings* Settings = UEspeonAnimSettings::Get();
	const FString Python = Settings->GetResolvedPython();
	const FString WorkDir = Settings->GetResolvedWorkingDir();
	if (!FPaths::FileExists(Python))
	{
		SetStatus([](FEspeonProcessorStatus& S)
		{
			S.State = EEspeonProcessorState::NotInstalled;
			S.Text = TEXT("Not installed");
		});
		return;
	}

	FPlatformMisc::SetEnvironmentVar(TEXT("PYTHONUTF8"), TEXT("1"));
	int32 Rc = -1;
	FString Out, Err;
	const bool bLaunched = FPlatformProcess::ExecProcess(*Python, TEXT("-m espeon_processor doctor"), &Rc, &Out, &Err, *WorkDir);
	TSharedPtr<FJsonObject> Doc;
	if (bLaunched)
	{
		// stdout is a single JSON document; tolerate stray lines before it.
		int32 Brace;
		if (Out.FindChar(TEXT('{'), Brace)) Doc = ParseJsonObject(Out.Mid(Brace));
	}
	if (!Doc.IsValid())
	{
		UE_LOG(LogEspeonSetup, Warning, TEXT("doctor failed (rc %d): %s %s"), Rc, *Out.Left(500), *Err.Left(500));
		SetStatus([](FEspeonProcessorStatus& S)
		{
			S.State = EEspeonProcessorState::Problems;
			S.Text = TEXT("Processor installation is broken - use Install / Repair");
		});
		return;
	}

	bool bOk = false;
	Doc->TryGetBoolField(TEXT("ok"), bOk);
	bool bCuda = false;
	Doc->TryGetBoolField(TEXT("cuda_available"), bCuda);
	FString Gpu;
	Doc->TryGetStringField(TEXT("gpu"), Gpu);
	FString FirstProblem;
	const TArray<TSharedPtr<FJsonValue>>* Problems = nullptr;
	if (Doc->TryGetArrayField(TEXT("problems"), Problems) && Problems->Num() > 0)
	{
		for (const TSharedPtr<FJsonValue>& P : *Problems)
		{
			const FString Msg = P->AsString();
			if (!Msg.Contains(TEXT("CPU fallback"))) { FirstProblem = Msg; break; }
		}
	}
	SetStatus([&](FEspeonProcessorStatus& S)
	{
		if (bOk)
		{
			S.State = EEspeonProcessorState::Ready;
			S.Text = bCuda && !Gpu.IsEmpty() ? FString::Printf(TEXT("Ready (GPU: %s)"), *Gpu) : FString(TEXT("Ready (CPU only - slow)"));
		}
		else
		{
			S.State = EEspeonProcessorState::Problems;
			S.Text = FString::Printf(TEXT("Needs repair: %s"), *FirstProblem);
		}
	});
}

bool FEspeonProcessorSetup::StartInstall(FString& OutError)
{
	if (bInstalling.Exchange(true))
	{
		OutError = TEXT("The installation is already running");
		return false;
	}
	const UEspeonAnimSettings* Settings = UEspeonAnimSettings::Get();
	const FString ProcessorDir = CleanDir(Settings->GetResolvedWorkingDir());
	const FString Script = FPaths::Combine(ProcessorDir, TEXT("tools/setup_processor.ps1"));
	if (!FPaths::FileExists(Script))
	{
		bInstalling = false;
		OutError = FString::Printf(TEXT("Setup script not found: %s (check the processor directory in Project Settings > EspeonAnim)"), *Script);
		return false;
	}
	SetStatus([](FEspeonProcessorStatus& S)
	{
		S.State = EEspeonProcessorState::Installing;
		S.Text = TEXT("Installing processor...");
		S.Progress = 0.f;
		S.StepMessage = TEXT("Starting");
	});
	Async(EAsyncExecution::ThreadPool, [this, Script, ProcessorDir]()
	{
		RunInstall(Script, ProcessorDir);
	});
	return true;
}

void FEspeonProcessorSetup::RunInstall(FString Script, FString ProcessorDir)
{
	const FString SetupLogFile = GetSetupLogPath();
	IFileManager::Get().MakeDirectory(*FPaths::GetPath(SetupLogFile), true);
	TUniquePtr<FArchive> Log(IFileManager::Get().CreateFileWriter(*SetupLogFile, FILEWRITE_AllowRead));
	auto LogLine = [&Log](const FString& Line)
	{
		if (!Log) return;
		FTCHARToUTF8 Utf8(*(Line + TEXT("\n")));
		Log->Serialize((void*)Utf8.Get(), Utf8.Length());
		Log->Flush();
	};

	auto Finish = [this](EEspeonProcessorState State, const FString& Text)
	{
		SetStatus([&](FEspeonProcessorStatus& S) { S.State = State; S.Text = Text; });
		bInstalling = false;
	};

	const FString PowerShell = FPaths::Combine(FPlatformMisc::GetEnvironmentVariable(TEXT("SystemRoot")), TEXT("System32/WindowsPowerShell/v1.0/powershell.exe"));
	const FString Exe = FPaths::FileExists(PowerShell) ? PowerShell : FString(TEXT("powershell.exe"));
	const FString Args = FString::Printf(
		TEXT("-NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"%s\" -ProcessorDir \"%s\" -AcceptModelLicenses"),
		*Script, *ProcessorDir);
	LogLine(FString::Printf(TEXT("> %s %s"), *Exe, *Args));

	void* OutRead = nullptr; void* OutWrite = nullptr;
	FPlatformProcess::CreatePipe(OutRead, OutWrite);
	uint32 Pid = 0;
	// stderr shares the stdout pipe so errors reach the log.
	FProcHandle Proc = FPlatformProcess::CreateProc(*Exe, *Args, false, true, true, &Pid, 0, *ProcessorDir, OutWrite, nullptr, OutWrite);
	if (!Proc.IsValid())
	{
		FPlatformProcess::ClosePipe(OutRead, OutWrite);
		LogLine(TEXT("Could not launch powershell.exe"));
		Finish(EEspeonProcessorState::InstallFailed, TEXT("Install failed: could not launch powershell.exe"));
		return;
	}

	FString Buf, LastError;
	auto HandleLine = [&](const FString& Line)
	{
		const FString T = Line.TrimEnd();
		if (T.IsEmpty()) return;
		LogLine(T);
		static const FString Prefix = TEXT("ESPEON_SETUP ");
		if (!T.StartsWith(Prefix)) return;
		TSharedPtr<FJsonObject> Obj = ParseJsonObject(T.Mid(Prefix.Len()));
		if (!Obj.IsValid()) return;
		double P = 0; FString Msg;
		Obj->TryGetNumberField(TEXT("progress"), P);
		Obj->TryGetStringField(TEXT("message"), Msg);
		if (Msg.StartsWith(TEXT("ERROR:"))) LastError = Msg.Mid(6).TrimStart();
		SetStatus([&](FEspeonProcessorStatus& S)
		{
			S.Progress = FMath::Clamp(float(P), 0.f, 1.f);
			S.StepMessage = Msg;
		});
	};
	auto Drain = [&]()
	{
		Buf += FPlatformProcess::ReadPipe(OutRead);
		int32 Idx;
		while (Buf.FindChar(TEXT('\n'), Idx)) { HandleLine(Buf.Left(Idx)); Buf.RightChopInline(Idx + 1); }
	};
	while (FPlatformProcess::IsProcRunning(Proc))
	{
		Drain();
		FPlatformProcess::Sleep(0.1f);
	}
	Drain();
	if (!Buf.IsEmpty()) HandleLine(Buf);
	int32 Rc = -1;
	FPlatformProcess::GetProcReturnCode(Proc, &Rc);
	FPlatformProcess::CloseProc(Proc);
	FPlatformProcess::ClosePipe(OutRead, OutWrite);
	LogLine(FString::Printf(TEXT("setup exited with code %d"), Rc));

	if (Rc != 0)
	{
		const FString Reason = LastError.IsEmpty() ? FString::Printf(TEXT("exit code %d, see %s"), Rc, *SetupLogFile) : LastError;
		UE_LOG(LogEspeonSetup, Error, TEXT("Processor setup failed: %s"), *Reason);
		Finish(EEspeonProcessorState::InstallFailed, FString::Printf(TEXT("Install failed: %s"), *Reason));
		return;
	}
	// Re-run doctor with the fresh install (on this thread), then release the installing flag.
	RunDoctor();
	bInstalling = false;
}

#undef LOCTEXT_NAMESPACE
