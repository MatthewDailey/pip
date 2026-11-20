import { NextResponse, type NextRequest } from "next/server";
import { HOST_TO_KEY } from "@/lib/screens/demoPage";

/** Map lookalike demo hosts (see emulator hosts + adb reverse) onto /demo/screens/:key. */
export function middleware(req: NextRequest) {
  const host = (req.headers.get("host") ?? "").split(":")[0]?.toLowerCase() ?? "";
  const key = HOST_TO_KEY[host];
  if (!key) return NextResponse.next();
  const path = req.nextUrl.pathname;
  if (path.startsWith("/api/") || path.startsWith("/demo/") || path.startsWith("/_next/")) return NextResponse.next();
  const url = req.nextUrl.clone();
  url.pathname = `/demo/screens/${key}`;
  return NextResponse.rewrite(url);
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
