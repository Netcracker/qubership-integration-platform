/**
 * @jest-environment jsdom
 */

import { describe, it, expect } from "@jest/globals";
import "@testing-library/jest-dom";
import { render, screen } from "@testing-library/react";
import { ConfigProvider } from "antd";
import { StatusTag } from "../../../src/components/labels/StatusTag.tsx";
import {
  BulkDeploymentStatus,
  ImportInstructionStatus,
  ImportEntityStatus,
  SystemImportStatus,
} from "../../../src/api/apiTypes.ts";

describe("StatusTag", () => {
  it("renders CREATED deployment status with success color", () => {
    render(<StatusTag status={BulkDeploymentStatus.CREATED} />);
    expect(screen.getByText("Created")).toBeInTheDocument();
  });

  it("renders IGNORED deployment status", () => {
    render(<StatusTag status={BulkDeploymentStatus.IGNORED} />);
    expect(screen.getByText("Ignored")).toBeInTheDocument();
  });

  it("renders FAILED_DEPLOY deployment status", () => {
    render(<StatusTag status={BulkDeploymentStatus.FAILED_DEPLOY} />);
    expect(screen.getByText("Failed deploy")).toBeInTheDocument();
  });

  it("renders FAILED_SNAPSHOT deployment status", () => {
    render(<StatusTag status={BulkDeploymentStatus.FAILED_SNAPSHOT} />);
    expect(screen.getByText("Failed snapshot")).toBeInTheDocument();
  });

  it("renders import CREATED status (backward compatibility)", () => {
    render(<StatusTag status={ImportEntityStatus.CREATED} />);
    expect(screen.getByText("Created")).toBeInTheDocument();
  });

  it("renders import ERROR status (backward compatibility)", () => {
    render(<StatusTag status={ImportEntityStatus.ERROR} />);
    expect(screen.getByText("Error")).toBeInTheDocument();
  });

  it("renders system UPDATED status", () => {
    render(<StatusTag status={SystemImportStatus.UPDATED} />);
    expect(screen.getByText("Updated")).toBeInTheDocument();
  });

  it("renders tooltip when message is provided", () => {
    render(
      <StatusTag
        status={BulkDeploymentStatus.FAILED_DEPLOY}
        message="Some error"
      />,
    );
    expect(screen.getByText("Failed deploy")).toBeInTheDocument();
  });

  it("renders tag without tooltip when message is absent", () => {
    const { container } = render(
      <StatusTag status={BulkDeploymentStatus.CREATED} />,
    );
    expect(
      container.querySelector(".ant-tooltip-open"),
    ).not.toBeInTheDocument();
  });

  it("renders NO_ACTION status with neutral token colors", () => {
    const { container } = render(
      <ConfigProvider
        theme={{
          token: {
            colorFillQuaternary: "#303030",
            colorBorderSecondary: "#515151",
            colorTextSecondary: "#9ca3af",
          },
        }}
      >
        <StatusTag status={ImportInstructionStatus.NO_ACTION} />
      </ConfigProvider>,
    );

    const tag = container.querySelector(".ant-tag");
    expect(tag).toHaveTextContent("No action");
    expect(tag).toHaveStyle({
      backgroundColor: "#303030",
      borderColor: "#515151",
      color: "#9ca3af",
    });
  });

  it("renders empty tag when status is undefined", () => {
    const { container } = render(<StatusTag />);
    expect(container.querySelector(".ant-tag")).toBeInTheDocument();
  });
});
