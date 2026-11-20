import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Pip · Jev fraud-detection playground",
  description: "Live phone-scam risk meter powered by Jev decision model, with baselines for comparison.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
