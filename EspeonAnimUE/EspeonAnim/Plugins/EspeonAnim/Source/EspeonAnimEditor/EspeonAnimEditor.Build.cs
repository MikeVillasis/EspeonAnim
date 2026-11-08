using UnrealBuildTool;

public class EspeonAnimEditor : ModuleRules
{
	public EspeonAnimEditor(ReadOnlyTargetRules Target) : base(Target)
	{
		PCHUsage = PCHUsageMode.UseExplicitOrSharedPCHs;

		// Vendored Nayuki QR code generator (Private/ThirdParty/QrCodeGenerator) throws std exceptions
		// and has its own `using` declarations, so keep exceptions on and unity builds off.
		bEnableExceptions = true;
		bUseUnity = false;

		PublicDependencyModuleNames.AddRange(new string[] { "Core", "CoreUObject", "Engine", "DeveloperSettings" });

		PrivateDependencyModuleNames.AddRange(new string[]
		{
			"UnrealEd", "Json", "Slate", "SlateCore", "ToolMenus", "DesktopPlatform",
			"AssetRegistry", "InputCore", "EditorFramework", "HTTP", "HTTPServer", "Sockets",
			"FileUtilities", "ApplicationCore", "Projects"
		});
	}
}
