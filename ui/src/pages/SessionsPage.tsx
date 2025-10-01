import { Flex } from "antd";
import React from "react";
import commonStyles from "../components/admin_tools/CommonStyle.module.css";
import Title from "antd/lib/typography/Title";
import { Sessions } from "./Sessions.tsx";
import { Icon } from "../IconProvider.tsx";

export const SessionsPage: React.FC = () => {
  return (
    <Flex vertical className={commonStyles["container"]}>
      <Flex vertical={false}>
        <Title level={4} className={commonStyles["title"]}>
          <Icon name="snippets" className={commonStyles["icon"]} />
          Sessions
        </Title>
      </Flex>
      <Sessions />
    </Flex>
  );
};
